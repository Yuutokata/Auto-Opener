package de.yuuto.autoOpener.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

@Serializable
data class ErrorResponse(val error: String)

fun Application.configureHttp(clientIpHeader: String?) {
    val logger = LoggerFactory.getLogger("ErrorHandler")

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
    }

    install(DefaultHeaders) {
        header("Content-Security-Policy", "default-src 'self'")
        header("X-Frame-Options", "DENY")
        header("X-Content-Type-Options", "nosniff")
        header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        header("Referrer-Policy", "no-referrer-when-downgrade")
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path() != "/health" }
    }

    install(StatusPages) {
        val invalidBody = ErrorResponse("Invalid request body. Please check your JSON structure.")
        exception<ContentTransformationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, invalidBody) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, invalidBody) }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            // Shows which key is being throttled, which is what you need to tell a shared proxy bucket from a noisy client.
            logger.warn(
                "Rate limited {} {}: client={} peer={} forwarded={}",
                call.request.httpMethod.value, call.request.path(), call.clientIp(clientIpHeader),
                call.request.origin.remoteAddress, clientIpHeader?.let { call.request.headers[it] },
            )
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            logger.error("Unhandled error on {}", call.request.path(), cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }
}
