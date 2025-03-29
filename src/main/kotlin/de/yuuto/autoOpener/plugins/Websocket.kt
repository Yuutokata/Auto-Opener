package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.webSocketManager
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.logging.Logger

import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import kotlin.math.log
import kotlin.time.Duration.Companion.seconds


fun Application.configureWebsockets() {
    val logger = LoggerFactory.getLogger("WebSocketRoute")
    install(WebSockets) {
        pingPeriod = 25.seconds
        timeout = 45.seconds
        maxFrameSize = 65536
        masking = false
    }

    routing {
        authenticate("auth-user") {
            webSocket("/listen/{userId}") {
                val principal = call.principal<JWTPrincipal>()
                val jwtUserId = principal?.payload?.getClaim("token")?.asString()
                val pathUserId = call.parameters["userId"]
                val role = principal?.payload?.getClaim("role")?.asString()

                if (role != "user") {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid role"))
                    return@webSocket
                }

                validateSession(jwtUserId, pathUserId, logger)?.let { reason ->
                    close(reason)
                    return@webSocket
                }

                try {
                    webSocketManager.handleSession(this, jwtUserId!!)
                } catch (e: Exception) {
                    logger.error("WebSocket error: ${e.message}", e)
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Server error"))
                }
            }
        }
    }

    webSocketManager.monitorConnections()
}

private fun validateSession(jwtUserId: String?, pathUserId: String?, logger: Logger): CloseReason? {
    // Log the exact values for debugging
    logger.info("Comparing: '$jwtUserId' vs '$pathUserId'")

    return when {
        jwtUserId == null || pathUserId == null -> {
            logger.warn("Null user ID detected: JWT=$jwtUserId, Path=$pathUserId")
            CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid credentials")
        }
        jwtUserId.trim() != pathUserId.trim() -> {
            logger.warn("User ID mismatch: JWT=$jwtUserId, Path=$pathUserId")
            CloseReason(CloseReason.Codes.VIOLATED_POLICY, "User ID mismatch")
        }
        else -> {
            logger.info("User ID match confirmed: $jwtUserId")
            null
        }
    }
}