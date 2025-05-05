package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.util.Config
import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.WebSocketManager
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.logging.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureWebsockets(
    dispatcherProvider: DispatcherProvider, webSocketManager: WebSocketManager
) {
    val logger = LoggerFactory.getLogger("WebSocketConfig")
    val supervisorJob = SupervisorJob()
    val scope = CoroutineScope(dispatcherProvider.monitoring + supervisorJob)

    install(WebSockets) {
        pingPeriod = null
        timeout = Config.getWebSocketTimeout().seconds
        maxFrameSize = Config.getWebSocketMaxFrameSize()
        masking = Config.getWebSocketMasking()
    }

    intercept(ApplicationCallPipeline.Plugins) {
        when {
            call.request.path().startsWith("/listen/") -> handleProtocolHeader(call)
            call.request.path() == "/bot" -> handleProtocolHeader(call)
        }
    }

    routing {
        rateLimit(RateLimitName("websocket")) {
            authenticate("auth-user") {
                webSocketRaw("/listen/{userId}") {
                    val callId = UUID.randomUUID().toString()
                    var connectionId: String? = null
                    var userIdForLog: String? = null
                    var roleForLog: String? = null
                    var connectionEstablished = false

                    try {
                        MDC.put("call_id", callId)
                        MDC.put("route", "/listen/{userId}")
                        MDC.put("websocket_type", "user")
                        logger.info("Incoming user WebSocket connection attempt")

                        val protocol = call.request.headers["Sec-WebSocket-Protocol"]
                        if (protocol != null && !isValidJwtStructure(protocol)) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid token structure"))
                            return@webSocketRaw
                        }

                        val principal = call.principal<JWTPrincipal>()
                        val jwtUserId = principal?.payload?.getClaim("token")?.asString()
                        val pathUserId = call.parameters["userId"]
                        val role = principal?.payload?.getClaim("role")?.asString()
                        userIdForLog = jwtUserId ?: pathUserId
                        roleForLog = role
                        MDC.put("path_user_id", pathUserId ?: "null")
                        MDC.put("jwt_user_id", jwtUserId ?: "null")
                        MDC.put("jwt_role", role ?: "null")

                        if (role != "user") {
                            MDC.put("validation_error", "Invalid role")
                            logger.warn("WebSocket connection rejected: Invalid role for user endpoint")
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid role"))
                            MDC.clear()
                            return@webSocketRaw
                        }

                        val closeReason = validateSession(jwtUserId, pathUserId, logger)
                        if (closeReason != null) {
                            MDC.put("validation_error", closeReason.message)
                            logger.warn("WebSocket connection rejected: ${closeReason.message}")
                            close(closeReason)
                            MDC.clear()
                            return@webSocketRaw
                        }

                        userIdForLog = jwtUserId!!
                        MDC.put("user_id", userIdForLog)

                        connectionId = "${userIdForLog}_${UUID.randomUUID()}"
                        MDC.put("connection_id", connectionId)

                        logger.debug("Cleaning up existing connections for user")
                        cleanupExistingConnections(userIdForLog, webSocketManager)

                        logger.info("User WebSocket connection validated, handing off to manager")
                        connectionEstablished = true
                        webSocketManager.handleSession(this, userIdForLog, connectionId)

                    } catch (e: Exception) {
                        MDC.put("event_type", "websocket_setup_error")
                        userIdForLog?.let { MDC.put("user_id", it) }
                        roleForLog?.let { MDC.put("role", it) }
                        connectionId?.let { MDC.put("connection_id", it) }
                        MDC.put("error_type", e::class.simpleName ?: "UnknownException")
                        logger.error("Error during user WebSocket setup or handling", e)
                        try {
                            if (isActive) {
                                close(
                                    CloseReason(
                                        CloseReason.Codes.INTERNAL_ERROR, "Server setup error: ${e.message?.take(50)}"
                                    )
                                )
                            }
                        } catch (closeEx: Exception) { /* Ignore close error */
                        }
                    } finally {
                        if (!connectionEstablished) {
                            MDC.put("event_type", "websocket_connection_failed")
                            userIdForLog?.let { MDC.put("user_id", it) }
                            roleForLog?.let { MDC.put("role", it) }
                            connectionId?.let { MDC.put("connection_id", it) }
                            logger.warn("User WebSocket connection failed before manager handoff")
                        }
                        MDC.clear()
                    }
                }
            }
        }
        rateLimit(RateLimitName("service")) {
            authenticate("auth-service") {
                webSocketRaw("/bot") {
                    val callId = UUID.randomUUID().toString()
                    var connectionId: String? = null
                    var botTokenIdentifier: String? = null
                    var roleForLog: String? = null
                    var connectionEstablished = false

                    try {
                        MDC.put("call_id", callId)
                        MDC.put("route", "/bot")
                        MDC.put("websocket_type", "bot")
                        logger.info("Incoming bot WebSocket connection attempt")

                        val protocol = call.request.headers["Sec-WebSocket-Protocol"]
                        if (protocol != null && !isValidJwtStructure(protocol)) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid token structure"))
                            logger.info("[SEC-WebSocket-Protocol] {}", protocol)
                            return@webSocketRaw
                        }

                        val principal = call.principal<JWTPrincipal>()
                        val jwtToken = principal?.payload?.getClaim("token")?.asString()
                        val role = principal?.payload?.getClaim("role")?.asString()
                        botTokenIdentifier = jwtToken?.take(8) + "..."
                        roleForLog = role
                        MDC.put("bot_token_prefix", botTokenIdentifier)
                        MDC.put("jwt_role", role ?: "null")

                        if (role != "service") {
                            MDC.put("validation_error", "Invalid role")
                            logger.warn("Bot WebSocket connection rejected: Invalid role")
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid role for bot"))
                            MDC.clear()
                            return@webSocketRaw
                        }

                        val botUuid = UUID.randomUUID()
                        connectionId = "bot_${botUuid}"
                        MDC.put("connection_id", connectionId)
                        MDC.put("bot_uuid", botUuid.toString())

                        logger.info("Bot WebSocket connection validated, handing off to manager")
                        connectionEstablished = true
                        webSocketManager.handleBotSession(this, jwtToken!!, connectionId)
                    } catch (e: Exception) {
                        MDC.put("event_type", "websocket_setup_error")
                        roleForLog?.let { MDC.put("role", it) }
                        connectionId?.let { MDC.put("connection_id", it) }
                        MDC.put("bot_token_prefix", botTokenIdentifier ?: "null")
                        MDC.put("error_type", e::class.simpleName ?: "UnknownException")
                        logger.error("Error during bot WebSocket setup or handling", e)
                        try {
                            if (isActive) {
                                close(
                                    CloseReason(
                                        CloseReason.Codes.INTERNAL_ERROR, "Server setup error: ${e.message?.take(50)}"
                                    )
                                )
                            }
                        } catch (closeEx: Exception) { /* Ignore close error */
                        }
                    } finally {
                        if (!connectionEstablished) {
                            MDC.put("event_type", "websocket_connection_failed")
                            roleForLog?.let { MDC.put("role", it) }
                            connectionId?.let { MDC.put("connection_id", it) }
                            MDC.put("bot_token_prefix", botTokenIdentifier ?: "null")
                            logger.warn("Bot WebSocket connection failed before manager handoff")
                        }
                        MDC.clear()
                    }
                }
            }
        }
    }

    scope.launch {
        logger.info("Launching WebSocket connection monitor")
        webSocketManager.monitorConnections()
    }
}

private fun validateSession(jwtUserId: String?, pathUserId: String?, logger: Logger): CloseReason? {
    MDC.put("validation_step", "user_id_match")
    MDC.put("path_user_id", pathUserId ?: "null")
    MDC.put("jwt_user_id", jwtUserId ?: "null")
    val reason = when {
        jwtUserId == null || pathUserId == null -> {
            logger.warn("User ID validation failed: Null ID detected")
            CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid credentials")
        }

        jwtUserId.trim() != pathUserId.trim() -> {
            logger.warn("User ID validation failed: Mismatch")
            CloseReason(CloseReason.Codes.VIOLATED_POLICY, "User ID mismatch")
        }

        else -> {
            logger.debug("User ID validation successful")
            null
        }
    }
    MDC.remove("validation_step")
    MDC.remove("path_user_id")
    MDC.remove("jwt_user_id")
    return reason
}

private suspend fun cleanupExistingConnections(userId: String, webSocketManager: WebSocketManager) {
    val sessionsToClose = webSocketManager.getActiveSessionsForUser(userId)
    if (sessionsToClose.isNotEmpty()) {
        MDC.put("event_type", "stale_connection_cleanup_start")
        MDC.put("user_id", userId)
        MDC.put("stale_session_count", sessionsToClose.size.toString())
        webSocketManager.logger.info("Cleaning up existing connections for user")
        MDC.clear()

        sessionsToClose.forEach { connectionId ->
            webSocketManager.cleanupConnection(
                connectionId, "stale_connection" to "New connection established for user $userId"
            )
        }
    } else {
        MDC.put("event_type", "stale_connection_check")
        MDC.put("user_id", userId)
        MDC.put("stale_session_count", "0")
        webSocketManager.logger.debug("No existing connections found for user to cleanup")
        MDC.clear()
    }
}

private fun handleProtocolHeader(call: PipelineCall) {
    call.request.headers["Sec-WebSocket-Protocol"]?.let { protocol ->
        call.response.headers.append("Sec-WebSocket-Protocol", protocol)
    }
}

