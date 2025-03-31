package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.RedisManager
import de.yuuto.autoOpener.util.WebSocketManager
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.logging.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.time.Duration.Companion.seconds


fun Application.configureWebsockets(
    dispatcherProvider: DispatcherProvider, redisManager: RedisManager, webSocketManager: WebSocketManager
) {
    val logger = LoggerFactory.getLogger("WebSocketLogger")
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

                logger.debug("JWT User ID: $jwtUserId, Path User ID: $pathUserId, Role: $role")

                val connectionId = "${jwtUserId}_${UUID.randomUUID()}"

                redisManager.monitorSubscription(jwtUserId.toString(), connectionId)

                if (role != "user") {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid role"))
                    return@webSocket
                }

                val closeReason = withContext(dispatcherProvider.processing) {
                    validateSession(jwtUserId, pathUserId, logger)
                }

                closeReason?.let {
                    close(it)
                    return@webSocket
                }

                try {
                    logger.info("[CONN|{}] Opening connection", connectionId)
                    withContext(dispatcherProvider.websocket) {
                        webSocketManager.handleSession(this@webSocket, jwtUserId!!, connectionId)
                    }
                    logger.info("[CONN|{}] Connection active", connectionId)

                } catch (e: Exception) {
                    logger.error("[CONN|{}] Error: {}", connectionId, e.message)
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Server error"))
                } finally {
                    logger.info("[CONN|{}] Closing connection", connectionId)
                    val userId = webSocketManager.extractUserId(connectionId)
                    webSocketManager.cleanupConnection(connectionId, userId)
                }
            }
        }
    }
    launch(dispatcherProvider.monitoring) {
        webSocketManager.monitorConnections()
    }
}

private fun validateSession(jwtUserId: String?, pathUserId: String?, logger: Logger): CloseReason? {
    logger.debug("Comparing: '$jwtUserId' vs '$pathUserId'")

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
            logger.debug("User ID match confirmed: $jwtUserId")
            null
        }
    }
}
