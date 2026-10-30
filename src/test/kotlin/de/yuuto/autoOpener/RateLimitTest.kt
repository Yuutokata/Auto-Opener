package de.yuuto.autoOpener

import de.yuuto.autoOpener.routes.TokenRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimitTest {
    private val limited = mapOf("RATE_LIMIT_TOKEN" to "2", "CLIENT_IP_HEADER" to "X-Forwarded-For")

    private suspend fun TestApp.tokenCall(forwardedFor: String): HttpStatusCode =
        client.post("/token") {
            header("X-Forwarded-For", forwardedFor)
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(SERVICE_TOKEN, "service"))
        }.status

    @Test
    fun `token requests are limited per client ip`() = runApp(testConfig(limited)) {
        assertEquals(HttpStatusCode.OK, tokenCall("203.0.113.1"))
        assertEquals(HttpStatusCode.OK, tokenCall("203.0.113.1"))
        assertEquals(HttpStatusCode.TooManyRequests, tokenCall("203.0.113.1"))
        // Another client is not affected by the first one's exhausted bucket.
        assertEquals(HttpStatusCode.OK, tokenCall("203.0.113.2"))
    }

    @Test
    fun `a forged leading forwarded-for entry does not buy extra requests`() = runApp(testConfig(limited)) {
        assertEquals(HttpStatusCode.OK, tokenCall("1.1.1.1, 203.0.113.9"))
        assertEquals(HttpStatusCode.OK, tokenCall("2.2.2.2, 203.0.113.9"))
        assertEquals(HttpStatusCode.TooManyRequests, tokenCall("3.3.3.3, 203.0.113.9"))
    }
}
