package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.webSocketManager
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureWebsockets() {
    install(WebSockets) {
        pingPeriod = 25.seconds
        timeout = 45.seconds
        maxFrameSize = 65536
        masking = false
    }

    routing {
        authenticate("auth-jwt") {
            webSocket("/listen/{userId}") {
                val principal = call.principal<JWTPrincipal>()
                val jwtUserId = principal?.payload?.getClaim("userId")?.asString()
                val pathUserId = call.parameters["userId"]

                validateSession(jwtUserId, pathUserId)?.let { reason ->
                    close(reason)
                    return@webSocket
                }

                try {
                    webSocketManager.handleSession(this, jwtUserId!!)
                } catch (e: Exception) {
                    application.log.error("WebSocket error: ${e.message}", e)
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Server error"))
                }
            }
        }
    }

    webSocketManager.monitorConnections()
}

private fun validateSession(jwtUserId: String?, pathUserId: String?): CloseReason? {
    return when {
        jwtUserId == null || pathUserId == null -> CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid credentials")

        jwtUserId != pathUserId -> CloseReason(CloseReason.Codes.VIOLATED_POLICY, "User ID mismatch")

        else -> null
    }
}