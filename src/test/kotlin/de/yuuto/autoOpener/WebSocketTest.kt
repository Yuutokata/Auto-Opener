package de.yuuto.autoOpener

import de.yuuto.autoOpener.routes.SingleUserRequest
import de.yuuto.autoOpener.routes.UserInput
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WebSocketTest {
    private suspend fun TestApp.addUser(id: String = USER_ID) {
        users.upsert(id, "user-$id", null)
    }

    @Test
    fun `rejects the handshake without a valid token`() = runApp {
        addUser()
        assertFailsWith<Exception> { client.webSocket("/listen/$USER_ID") { } }
        assertFailsWith<Exception> {
            client.webSocket("/listen/$USER_ID", request = { bearerAuth("garbage") }) { }
        }
    }

    @Test
    fun `a user token cannot open the bot socket and vice versa`() = runApp {
        addUser()
        val userJwt = userToken()
        val serviceJwt = serviceToken()
        assertFailsWith<Exception> { client.webSocket("/bot", request = { bearerAuth(userJwt) }) { } }
        assertFailsWith<Exception> { client.webSocket("/listen/$USER_ID", request = { bearerAuth(serviceJwt) }) { } }
    }

    @Test
    fun `closes the socket when the path does not match the token`() = runApp {
        addUser()
        addUser(OTHER_USER_ID)
        val jwt = userToken()
        client.webSocket("/listen/$OTHER_USER_ID", request = { bearerAuth(jwt) }) {
            assertEquals("User ID mismatch", withTimeout(5.seconds) { closeReason.await() }?.message)
        }
    }

    @Test
    fun `accepts the token as websocket subprotocol and echoes it`() = runApp {
        addUser()
        val jwt = userToken()
        val session = client.webSocketSession("/listen/$USER_ID") { header("Sec-WebSocket-Protocol", jwt) }
        assertEquals(jwt, session.call.response.headers["Sec-WebSocket-Protocol"])
        awaitConnections(USER_ID, 1)
        session.close()
    }

    @Test
    fun `send_message delivers the bare url to every live connection of the user`() = runApp {
        addUser()
        val jwt = userToken()
        val service = serviceToken()
        client.webSocket("/listen/$USER_ID", request = { bearerAuth(jwt) }) {
            awaitConnections(USER_ID, 1)
            val response = client.post("/send_message?user_id=$USER_ID") {
                bearerAuth(service)
                contentType(ContentType.Application.Json)
                setBody("""{"message":"found","url":"https://shop.example/item"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("https://shop.example/item", (withTimeout(5.seconds) { incoming.receive() } as Frame.Text).readText())
        }
    }

    @Test
    fun `send_message reports an offline user`() = runApp {
        addUser()
        val response = client.post("/send_message?user_id=$USER_ID") {
            bearerAuth(serviceToken())
            contentType(ContentType.Application.Json)
            setBody("""{"message":"found","url":"https://shop.example/item"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("No active sessions"))
    }

    @Test
    fun `the bot socket forwards messages to the user and confirms delivery`() = runApp {
        addUser()
        val jwt = userToken()
        val service = serviceToken()
        client.webSocket("/listen/$USER_ID", request = { bearerAuth(jwt) }) {
            awaitConnections(USER_ID, 1)
            client.webSocket("/bot", request = { bearerAuth(service) }) {
                send(Frame.Text("""{"userId":"$USER_ID","message":{"message":"hit","url":"https://shop.example/a"}}"""))
                val reply = (withTimeout(5.seconds) { incoming.receive() } as Frame.Text).readText()
                assertTrue(reply.contains("\"status\":\"success\""), reply)
                assertTrue(reply.contains("1/1"), reply)
            }
            val forwarded = (withTimeout(5.seconds) { incoming.receive() } as Frame.Text).readText()
            assertEquals("""{"message":"hit","url":"https://shop.example/a"}""", forwarded)
        }
    }

    @Test
    fun `the bot socket reports offline users and invalid frames`() = runApp {
        val service = serviceToken()
        client.webSocket("/bot", request = { bearerAuth(service) }) {
            send(Frame.Text("""{"userId":"$USER_ID","message":{"message":"hit","url":"u"}}"""))
            assertTrue((withTimeout(5.seconds) { incoming.receive() } as Frame.Text).readText().contains("\"status\":\"warn\""))

            send(Frame.Text("""{"userId":"abc","message":{"message":"hit","url":"u"}}"""))
            assertTrue((withTimeout(5.seconds) { incoming.receive() } as Frame.Text).readText().contains("Invalid user ID format"))

            send(Frame.Text("not json"))
            assertTrue((withTimeout(5.seconds) { incoming.receive() } as Frame.Text).readText().contains("Invalid message format"))
        }
    }

    @Test
    fun `the oldest connection is closed when the per-user limit is exceeded`() = runApp(testConfig(mapOf("MAX_CONNECTIONS_PER_USER" to "2"))) {
        addUser()
        val jwt = userToken()
        val first = client.webSocketSession("/listen/$USER_ID") { bearerAuth(jwt) }
        awaitConnections(USER_ID, 1)
        val second = client.webSocketSession("/listen/$USER_ID") { bearerAuth(jwt) }
        awaitConnections(USER_ID, 2)
        val third = client.webSocketSession("/listen/$USER_ID") { bearerAuth(jwt) }

        val reason = withTimeout(5.seconds) { first.closeReason.await() }
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        awaitConnections(USER_ID, 2)
        second.close()
        third.close()
    }

    @Test
    fun `a removed user is disconnected`() = runApp {
        addUser()
        val jwt = userToken()
        val service = serviceToken()
        val session = client.webSocketSession("/listen/$USER_ID") { bearerAuth(jwt) }
        awaitConnections(USER_ID, 1)

        client.post("/users/remove") {
            bearerAuth(service)
            contentType(ContentType.Application.Json)
            setBody(de.yuuto.autoOpener.routes.UserRemoveRequest(USER_ID))
        }
        assertEquals("User removed", withTimeout(5.seconds) { session.closeReason.await() }?.message)
    }
}
