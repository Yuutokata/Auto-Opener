package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.WebSocketManager
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
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
    dispatcherProvider: DispatcherProvider, webSocketManager: WebSocketManager
) {
    val supervisorJob = SupervisorJob()
    val scope = CoroutineScope(dispatcherProvider.monitoring + supervisorJob)

    install(WebSockets) {
        pingPeriod = null
        timeout = 50.seconds
        maxFrameSize = 65536
        masking = false
    }

    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.path().startsWith("/listen/")) {
            val protocol = call.request.headers["Sec-WebSocket-Protocol"]
            if (protocol != null) {
                call.response.headers.append("Sec-WebSocket-Protocol", protocol)
            }
        }
    }

    routing {
        authenticate("auth-user") {
            webSocketRaw("/listen/{userId}") {
                val protocol = call.request.headers["Sec-WebSocket-Protocol"]
                if (protocol != null && !isValidJwtStructure(protocol)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid token structure"))
                    return@webSocketRaw
                }

                val principal = call.principal<JWTPrincipal>()
                val jwtUserId = principal?.payload?.getClaim("token")?.asString()
                val pathUserId = call.parameters["userId"]
                val role = principal?.payload?.getClaim("role")?.asString()

                logger.debug("JWT User ID: $jwtUserId, Path User ID: $pathUserId, Role: $role")

                cleanupExistingConnections(jwtUserId.toString(), webSocketManager)

                val connectionId = "${jwtUserId}_${UUID.randomUUID()}"

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
                    webSocketManager.cleanupConnection(connectionId)
                }
            }
        }
        authenticate("auth-service") {
            webSocketRaw("/bot") {
                val protocol = call.request.headers["Sec-WebSocket-Protocol"]
                if (protocol != null && !isValidJwtStructure(protocol)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid token structure"))
                    return@webSocketRaw
                }

                val principal = call.principal<JWTPrincipal>()
                val jwtToken = principal?.payload?.getClaim("token")?.asString()
                val pathBotId = call.parameters["botId"]
                val role = principal?.payload?.getClaim("role")?.asString()
                logger.debug("JWT Bot Token: $jwtToken, Path Bot ID: $pathBotId, Role: $role")

                if (role != "service") {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid role for bot"))
                    return@webSocketRaw
                }

                val botId = UUID.randomUUID()

                val connectionId = "bot_${botId}"

                try {
                    logger.info("[CONN|{}] Opening bot connection", connectionId)
                    withContext(dispatcherProvider.websocket) {
                        webSocketManager.handleBotSession(this@webSocketRaw, botId.toString(), connectionId)
                    }
                    logger.info("[CONN|{}] Bot connection active", connectionId)
                } catch (e: Exception) {
                    logger.error("[CONN|{}] Error: {}", connectionId, e.message)
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Server error"))
                } finally {
                    logger.info("[CONN|{}] Closing bot connection", connectionId)
                    webSocketManager.cleanupConnection(connectionId)
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

private suspend fun cleanupExistingConnections(userId: String, webSocketManager: WebSocketManager) {
    webSocketManager.getActiveSessionsForUser(userId).forEach { connectionId ->
        if (webSocketManager.activeConnections.containsKey(connectionId)) {
            logger.info("Closing stale connection $connectionId")
            webSocketManager.cleanupConnection(connectionId)
        }
    }
}
