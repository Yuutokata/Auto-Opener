package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.dependencyProvider
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.time.Duration.Companion.seconds


private val logger = LoggerFactory.getLogger("WebSocketLogger")

fun Application.configureWebsockets(
    dispatcherProvider: DispatcherProvider, redisManager: RedisManager, webSocketManager: WebSocketManager
) {
    val supervisorJob = SupervisorJob()
    val scope = CoroutineScope(dispatcherProvider.monitoring + supervisorJob)

    install(WebSockets) {
        pingPeriod = null
        timeout = 50.seconds
        maxFrameSize = 65536
        masking = false
    }

    routing {
        authenticate("auth-user") {
            webSocketRaw("/listen/{userId}") {
                // Get the protocol from headers but don't try to set it in response
                val protocol = call.request.headers["Sec-WebSocket-Protocol"]
                // Protocol handling should be done before this point - log for debugging
                protocol?.let { logger.debug("Client requested protocol: $it") }

                val principal = call.principal<JWTPrincipal>()
                val jwtUserId = principal?.payload?.getClaim("token")?.asString()
                val pathUserId = call.parameters["userId"]
                val role = principal?.payload?.getClaim("role")?.asString()

                logger.debug("JWT User ID: $jwtUserId, Path User ID: $pathUserId, Role: $role")

                cleanupExistingConnections(jwtUserId.toString())

                val connectionId = "${jwtUserId}_${UUID.randomUUID()}"

                redisManager.monitorSubscription(jwtUserId.toString(), connectionId)

                if (role != "user") {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid role"))
                    return@webSocketRaw
                }

                val closeReason = withContext(dispatcherProvider.processing) {
                    validateSession(jwtUserId, pathUserId, logger)
                }

                closeReason?.let {
                    close(it)
                    return@webSocketRaw
                }

                try {
                    logger.info("[CONN|{}] Opening connection", connectionId)
                    withContext(dispatcherProvider.websocket) {
                        webSocketManager.handleSession(this@webSocketRaw, jwtUserId!!, connectionId)
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
    scope.launch {
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

private suspend fun cleanupExistingConnections(userId: String) {
    val currentConnectionId = "${userId}_${UUID.randomUUID()}" // Get actual new ID
    dependencyProvider.redisManager.getActiveSessionsForUser(userId)
        .filter { it != currentConnectionId } // Prevent killing new connection
        .forEach { connectionId ->
            if (dependencyProvider.webSocketManager.activeConnections.containsKey(connectionId)) {
                logger.info("Closing stale connection $connectionId")
                dependencyProvider.webSocketManager.cleanupConnection(connectionId, userId)
            }
        }
}

