package de.yuuto.autoOpener.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class HealthResponse(
    val status: String = "UP",
    val timestamp: Long = System.currentTimeMillis()
)

fun Route.healthRoute() {
    get("/health") {
        call.respond(HttpStatusCode.OK, HealthResponse())
    }
}
