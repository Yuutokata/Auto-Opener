package de.yuuto.autoOpener

import de.yuuto.autoOpener.routes.TokenRequest
import de.yuuto.autoOpener.users.UserSecrets
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenRoutesTest {
    private suspend fun TestApp.requestToken(token: String, role: String? = null, secret: String? = null) =
        client.post("/token") {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(token, role, secret))
        }

    @Test
    fun `issues a token for a known user without secret while rollout is open`() = runApp {
        users.upsert(USER_ID, "alice", null)
        val response = requestToken(USER_ID)
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"token\""))
    }

    @Test
    fun `rejects unknown users`() = runApp {
        assertEquals(HttpStatusCode.Unauthorized, requestToken(USER_ID).status)
    }

    @Test
    fun `rejects malformed user ids`() = runApp {
        assertEquals(HttpStatusCode.BadRequest, requestToken("not-a-snowflake").status)
        assertEquals(HttpStatusCode.BadRequest, requestToken(" ").status)
    }

    @Test
    fun `rejects unknown roles`() = runApp {
        assertEquals(HttpStatusCode.BadRequest, requestToken(USER_ID, role = "admin").status)
    }

    @Test
    fun `a secret that is sent must match`() = runApp {
        users.upsert(USER_ID, "alice", UserSecrets.hash(USER_SECRET))
        assertEquals(HttpStatusCode.OK, requestToken(USER_ID, secret = USER_SECRET).status)
        assertEquals(HttpStatusCode.Unauthorized, requestToken(USER_ID, secret = "cd".repeat(32)).status)
    }

    @Test
    fun `missing secret is tolerated only while the secret is optional`() = runApp {
        users.upsert(USER_ID, "alice", UserSecrets.hash(USER_SECRET))
        assertEquals(HttpStatusCode.OK, requestToken(USER_ID).status)
    }

    @Test
    fun `missing secret is rejected once the secret is required`() = runApp(testConfig(mapOf("REQUIRE_USER_SECRET" to "true"))) {
        users.upsert(USER_ID, "alice", UserSecrets.hash(USER_SECRET))
        users.upsert(OTHER_USER_ID, "bob", null)
        assertEquals(HttpStatusCode.Unauthorized, requestToken(USER_ID).status)
        assertEquals(HttpStatusCode.OK, requestToken(USER_ID, secret = USER_SECRET).status)
        assertEquals(HttpStatusCode.Unauthorized, requestToken(OTHER_USER_ID).status)
        assertEquals(HttpStatusCode.Unauthorized, requestToken(OTHER_USER_ID, secret = USER_SECRET).status)
    }

    @Test
    fun `issues service tokens only for configured tokens`() = runApp {
        assertEquals(HttpStatusCode.OK, requestToken(SERVICE_TOKEN, role = "service").status)
        assertEquals(HttpStatusCode.Unauthorized, requestToken("x".repeat(40), role = "service").status)
    }

    @Test
    fun `a service token is not exposed through the issued jwt`() = runApp {
        val jwt = serviceToken()
        val payload = String(java.util.Base64.getUrlDecoder().decode(jwt.split(".")[1]))
        assertFalse(payload.contains(SERVICE_TOKEN))
    }

    @Test
    fun `malformed bodies get a generic error`() = runApp {
        val response = client.post("/token") {
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertFalse(response.bodyAsText().contains("Exception"))
    }
}
