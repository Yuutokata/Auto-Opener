package de.yuuto.autoOpener

import de.yuuto.autoOpener.routes.SingleUserRequest
import de.yuuto.autoOpener.routes.UserInput
import de.yuuto.autoOpener.routes.UserRemoveRequest
import de.yuuto.autoOpener.routes.UsersList
import de.yuuto.autoOpener.users.UserSecrets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class UserRoutesTest {
    private suspend inline fun <reified T> TestApp.postAsService(path: String, body: T): HttpResponse {
        val jwt = serviceToken()
        return client.post(path) {
            bearerAuth(jwt)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    @Test
    fun `user management requires a service token`() = runApp {
        val anonymous = client.post("/users/add") {
            contentType(ContentType.Application.Json)
            setBody(SingleUserRequest(UserInput(USER_ID, "alice")))
        }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        users.upsert(USER_ID, "alice", null)
        val asUser = client.post("/users/remove") {
            bearerAuth(userToken())
            contentType(ContentType.Application.Json)
            setBody(UserRemoveRequest(USER_ID))
        }
        assertEquals(HttpStatusCode.Unauthorized, asUser.status)
        assertNotNull(users.find(USER_ID))
    }

    @Test
    fun `adds and then updates a user`() = runApp {
        val created = postAsService("/users/add", SingleUserRequest(UserInput(USER_ID, "alice")))
        assertEquals(HttpStatusCode.OK, created.status)
        assertTrue(created.bodyAsText().contains("\"operation\":\"created\""))

        val updated = postAsService("/users/add", SingleUserRequest(UserInput(USER_ID, "alice2")))
        assertTrue(updated.bodyAsText().contains("\"operation\":\"updated\""))
        assertEquals("alice2", users.find(USER_ID)?.name)
    }

    @Test
    fun `stores only a hash of the secret and keeps it on later updates`() = runApp {
        val response = postAsService("/users/add", SingleUserRequest(UserInput(USER_ID, "alice", USER_SECRET)))
        assertFalse(response.bodyAsText().contains(USER_SECRET))
        assertEquals(UserSecrets.hash(USER_SECRET), users.find(USER_ID)?.secretHash)

        postAsService("/users/add", SingleUserRequest(UserInput(USER_ID, "renamed")))
        assertEquals(UserSecrets.hash(USER_SECRET), users.find(USER_ID)?.secretHash)
    }

    @Test
    fun `validates ids and secrets`() = runApp {
        assertEquals(HttpStatusCode.BadRequest, postAsService("/users/add", SingleUserRequest(UserInput("", "x"))).status)
        assertEquals(HttpStatusCode.BadRequest, postAsService("/users/add", SingleUserRequest(UserInput("abc", "x"))).status)
        assertEquals(HttpStatusCode.BadRequest, postAsService("/users/add", SingleUserRequest(UserInput(USER_ID, "x", "short"))).status)
        assertNull(users.find(USER_ID))
    }

    @Test
    fun `removes users and reports unknown ones`() = runApp {
        users.upsert(USER_ID, "alice", null)
        assertEquals(HttpStatusCode.OK, postAsService("/users/remove", UserRemoveRequest(USER_ID)).status)
        assertNull(users.find(USER_ID))
        assertEquals(HttpStatusCode.NotFound, postAsService("/users/remove", UserRemoveRequest(USER_ID)).status)
    }

    @Test
    fun `bulk sync adds missing users, removes absent ones and keeps secrets`() = runApp {
        users.upsert(USER_ID, "alice", UserSecrets.hash(USER_SECRET))
        users.upsert("333456789012345678", "gone", null)

        val response = postAsService(
            "/users",
            UsersList(listOf(UserInput(USER_ID, "alice"), UserInput(OTHER_USER_ID, "bob"))),
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"added\":1") && body.contains("\"removed\":1") && body.contains("\"unchanged\":1"))
        assertNotNull(users.find(OTHER_USER_ID))
        assertNull(users.find("333456789012345678"))
        assertEquals(UserSecrets.hash(USER_SECRET), users.find(USER_ID)?.secretHash)
    }

    @Test
    fun `bulk sync rejects empty lists and duplicates`() = runApp {
        assertEquals(HttpStatusCode.BadRequest, postAsService("/users", UsersList(emptyList())).status)
        val duplicates = UsersList(listOf(UserInput(USER_ID, "a"), UserInput(USER_ID, "b")))
        assertEquals(HttpStatusCode.BadRequest, postAsService("/users", duplicates).status)
    }

    @Test
    fun `bulk sync disconnects users it removes`() = runApp {
        users.upsert(USER_ID, "alice", null)
        val jwt = userToken()
        val session = client.webSocketSession("/listen/$USER_ID") { bearerAuth(jwt) }
        awaitConnections(USER_ID, 1)

        postAsService("/users", UsersList(listOf(UserInput(OTHER_USER_ID, "bob"))))

        assertEquals("User removed", withTimeout(5.seconds) { session.closeReason.await() }?.message)
    }
}
