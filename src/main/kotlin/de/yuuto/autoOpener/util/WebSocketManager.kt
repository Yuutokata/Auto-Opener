package de.yuuto.autoOpener.util

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import de.yuuto.autoOpener.dataclass.BotResponse
import de.yuuto.autoOpener.dataclass.ConnectionMetrics
import de.yuuto.autoOpener.dataclass.WebSocketMessage
import de.yuuto.autoOpener.dataclass.WebsocketReceive
import io.ktor.websocket.*
import io.ktor.websocket.CloseReason.Codes.INTERNAL_ERROR
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class WebSocketManager(private val dispatcherProvider: DispatcherProvider) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcherProvider.monitoring + supervisorJob)
    internal val logger = LoggerFactory.getLogger(WebSocketManager::class.java)
    internal val activeConnections = ConcurrentHashMap<String, WebSocketSession>()
    internal val connectionTimestamps = ConcurrentHashMap<String, Long>()
    internal val connectionMetrics = ConcurrentHashMap<String, ConnectionMetrics>()
    private val pendingPings = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    // Optimize message deduplication cache with better expiration and initial capacity
    private val processedMessages: LoadingCache<String, Boolean> = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .initialCapacity(1000)
        .maximumSize(10000)
        .recordStats()
        .build { _ -> true }

    private val userSessions = ConcurrentHashMap<String, MutableSet<String>>()
    private val botSessions = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        scope.launch {
            while (isActive) {
                try {
                     synchronized(activeConnections) {
                         logConnectionStats()
                         verifySessionIntegrity()
                     }
                     verifySessions()
                     delay(60.seconds)
                } catch (e: CancellationException) {
                    logger.info("WebSocketManager init loop cancelled.")
                    break
                } catch (e: Exception) {
                    MDC.put("event_type", "manager_init_loop_error")
                    logger.error("Error in WebSocketManager init loop", e)
                    MDC.clear()
                    delay(5.seconds)
                }
            }
        }
    }

    fun shutdown() {
        activeConnections.clear()
        connectionTimestamps.clear()
        connectionMetrics.clear()
        MDC.put("event_type", "websocket_manager_shutdown")
        logger.info("WebSocketManager resources released")
        MDC.clear()
    }

    private fun storeUserSession(userId: String, connectionId: String) {
        userSessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(connectionId)
        MDC.put("event_type", "session_stored_user")
        MDC.put("connection_id", connectionId)
        MDC.put("user_id", userId)
        logger.debug("Stored user session")
        MDC.clear()
    }

    // Store bot sessions similar to user sessions
    private fun storeBotSession(botId: String, connectionId: String) {
        botSessions.computeIfAbsent(botId) { ConcurrentHashMap.newKeySet() }.add(connectionId)
        MDC.put("event_type", "session_stored_bot")
        MDC.put("connection_id", connectionId)
        MDC.put("bot_id", botId)
        logger.debug("Stored bot session")
        MDC.clear()
    }

    suspend fun handleSession(session: WebSocketSession, userId: String, connectionId: String) {
        try {
            registerConnection(connectionId, session, userId)
            processIncomingMessages(connectionId, userId)
        } catch (e: ClosedReceiveChannelException) {
            MDC.put("event_type", "connection_receive_closed")
            MDC.put("connection_id", connectionId)
            MDC.put("user_id", userId)
            logger.debug("Receive channel closed, likely graceful disconnect")
            MDC.clear()
        } catch (e: IOException) {
            MDC.put("event_type", "connection_io_error")
            MDC.put("connection_id", connectionId)
            MDC.put("user_id", userId)
            logger.error("IO error during WebSocket handling", e)
            MDC.clear()
        } catch (e: Exception) {
            MDC.put("event_type", "connection_unexpected_error")
            MDC.put("connection_id", connectionId)
            MDC.put("user_id", userId)
            logger.error("Unexpected error during WebSocket handling", e)
            MDC.clear()
        } finally {
            cleanupConnection(connectionId, determineCloseStatus(CloseReason(CloseReason.Codes.NORMAL, "Session closed")))
        }
    }

    // New: Handle bot session using processIncomingMessages
    suspend fun handleBotSession(session: WebSocketSession, botId: String, connectionId: String) {
        try {
            registerConnection(connectionId, session, botId)
            processIncomingMessages(connectionId, botId)
        } catch (e: ClosedReceiveChannelException) {
            MDC.put("event_type", "connection_receive_closed")
            MDC.put("connection_id", connectionId)
            MDC.put("bot_id", botId)
            logger.debug("Receive channel closed, likely graceful disconnect (bot)")
            MDC.clear()
        } catch (e: IOException) {
            MDC.put("event_type", "connection_io_error")
            MDC.put("connection_id", connectionId)
            MDC.put("bot_id", botId)
            logger.error("IO error during WebSocket handling (bot)", e)
            MDC.clear()
        } catch (e: Exception) {
            MDC.put("event_type", "connection_unexpected_error")
            MDC.put("connection_id", connectionId)
            MDC.put("bot_id", botId)
            logger.error("Unexpected error during WebSocket handling (bot)", e)
            MDC.clear()
        } finally {
            cleanupConnection(connectionId, determineCloseStatus(CloseReason(CloseReason.Codes.NORMAL, "Session closed")))
        }
    }

    internal fun determineCloseStatus(reason: CloseReason?): Pair<String, String> {
        return when (reason?.code) {
            CloseReason.Codes.NORMAL.code -> "graceful_disconnect" to (reason.message.takeIf { it.isNotBlank() } ?: "Client closed normally")
            CloseReason.Codes.GOING_AWAY.code -> "graceful_disconnect" to (reason.message.takeIf { it.isNotBlank() } ?: "Client navigated away or server shutdown")
            CloseReason.Codes.VIOLATED_POLICY.code -> "policy_violation" to (reason.message.takeIf { it.isNotBlank() } ?: "Policy violation")
            CloseReason.Codes.CANNOT_ACCEPT.code -> "cannot_accept" to (reason.message.takeIf { it.isNotBlank() } ?: "Cannot accept data")
            CloseReason.Codes.PROTOCOL_ERROR.code -> "protocol_error" to (reason.message.takeIf { it.isNotBlank() } ?: "Protocol error")
            else -> "unknown_disconnect" to (reason?.message?.takeIf { it.isNotBlank() } ?: "Unknown or abnormal closure")
        }
    }

    private suspend fun handleBotMessage(connectionId: String, frame: Frame.Text, botId: String) =
        withContext(dispatcherProvider.websocket) {
            val text = frame.readText()

            // Generate a message ID for deduplication
            val messageId = "${botId}_${System.currentTimeMillis()}_${text.hashCode()}"

            // Track message received
            val tags = MetricsService.createTags(
                "message_type" to "bot",
                "bot_id" to botId
            )
            MetricsService.incrementMessagesReceived(tags)

            // Check if this message was already processed (deduplication)
            if (processedMessages.get(messageId)) {
                // Track message deduplication
                MetricsService.incrementMessageDeduplication(tags)

                MDC.put("event_type", "message_duplicate")
                MDC.put("connection_id", connectionId)
                MDC.put("bot_id", botId)
                MDC.put("message_id", messageId)
                logger.debug("Duplicate message detected and skipped")
                MDC.clear()
                return@withContext
            }

            MDC.put("event_type", "message_received_bot")
            MDC.put("connection_id", connectionId)
            MDC.put("bot_id", botId)
            MDC.put("message_preview", text.take(50))
            MDC.put("message_id", messageId)
            logger.debug("Received bot message")
            MDC.clear()

            try {
                // Parse message early to fail fast if invalid
                val websocketReceive = Json.decodeFromString<WebsocketReceive>(text)

                // Validate user ID format
                if (!websocketReceive.userId.matches(Regex("^\\d{15,20}$"))) {
                    MDC.put("event_type", "message_validation_error")
                    MDC.put("connection_id", connectionId)
                    MDC.put("bot_id", botId)
                    MDC.put("invalid_user_id", websocketReceive.userId)
                    MDC.put("reason", "Invalid user ID format")
                    MDC.put("message_id", messageId)
                    logger.error("Invalid user ID format in bot message")
                    MDC.clear()
                    sendBotResponse(connectionId, "error", "Invalid user ID format", websocketReceive.userId)
                    return@withContext
                }

                val targetUserId = websocketReceive.userId

                // Pre-encode the message once instead of for each recipient
                val encodedMessage = Json.encodeToString(websocketReceive.message)

                // Get active sessions efficiently
                val activeSessions = getActiveSessionsForUser(targetUserId)
                if (activeSessions.isEmpty()) {
                    // Track message forward failure
                    val failureTags = MetricsService.createTags(
                        "reason" to "no_active_sessions",
                        "bot_id" to botId,
                        "user_id" to targetUserId
                    )
                    MetricsService.incrementMessageForwardFailures(failureTags)

                    MDC.put("event_type", "message_forward_failed")
                    MDC.put("connection_id", connectionId)
                    MDC.put("bot_id", botId)
                    MDC.put("user_id", targetUserId)
                    MDC.put("reason", "No active sessions found for user")
                    MDC.put("status", "session_not_found")
                    MDC.put("message_id", messageId)
                    logger.warn("No active sessions found for user, cannot forward message")
                    MDC.clear()
                    sendBotResponse(connectionId, "warn", "No active sessions found for user", targetUserId)
                    return@withContext
                }

                // Use a more efficient approach for tracking success
                var successCount = 0
                val failedSessions = mutableListOf<String>()

                // Measure message forwarding latency
                val forwardStartTime = System.currentTimeMillis()

                // Process all sessions in parallel for better performance
                val results = activeSessions.map { userConnectionId ->
                    async(dispatcherProvider.websocket) {
                        try {
                            sendMessageToClient(userConnectionId, encodedMessage, targetUserId)
                            true to userConnectionId
                        } catch (e: Exception) {
                            // Track message forward failure
                            val failureTags = MetricsService.createTags(
                                "reason" to "send_error",
                                "bot_id" to botId,
                                "user_id" to targetUserId
                            )
                            MetricsService.incrementMessageForwardFailures(failureTags)

                            MDC.put("event_type", "message_forward_failed")
                            MDC.put("source_connection_id", connectionId)
                            MDC.put("bot_id", botId)
                            MDC.put("user_id", targetUserId)
                            MDC.put("target_connection_id", userConnectionId)
                            MDC.put("status", "failure")
                            MDC.put("message_id", messageId)
                            logger.error("Failed to forward message to user session", e)
                            MDC.clear()
                            false to userConnectionId
                        }
                    }
                }.awaitAll()

                // Record message forwarding latency
                val forwardLatencyMs = System.currentTimeMillis() - forwardStartTime
                val latencyTags = MetricsService.createTags(
                    "bot_id" to botId,
                    "user_id" to targetUserId
                )
                MetricsService.recordMessageForwardLatency(forwardLatencyMs, latencyTags)

                // Process results
                results.forEach { (success, userConnectionId) ->
                    if (success) {
                        successCount++
                        // Track URL forward (business metric)
                        MetricsService.incrementUrlForwards(
                            MetricsService.createTags(
                                "bot_id" to botId,
                                "user_id" to targetUserId
                            )
                        )

                        MDC.put("event_type", "message_forwarded_to_client")
                        MDC.put("source_connection_id", connectionId)
                        MDC.put("bot_id", botId)
                        MDC.put("user_id", targetUserId)
                        MDC.put("target_connection_id", userConnectionId)
                        MDC.put("status", "success")
                        MDC.put("message_id", messageId)
                        logger.info("Message forwarded to user session")
                        MDC.clear()
                    } else {
                        failedSessions.add(userConnectionId)
                    }
                }

                // Update activity timestamp once after all processing
                updateActivityTimestamp(connectionId)

                // Mark message as processed
                processedMessages.put(messageId, true)

                // Calculate and track message delivery success rate
                val totalSessions = activeSessions.size
                val successRate = if (totalSessions > 0) (successCount * 100L) / totalSessions else 0L
                MetricsService.updateMessageDeliverySuccessRate(successRate)

                // Send appropriate response
                if (successCount > 0) {
                    val failureInfo = if (failedSessions.isNotEmpty()) 
                        " (${failedSessions.size} sessions failed)" else ""

                    sendBotResponse(
                        connectionId, 
                        "success", 
                        "Message delivered to $successCount/${activeSessions.size} active sessions$failureInfo", 
                        targetUserId
                    )
                } else {
                    // Track complete delivery failure
                    val failureTags = MetricsService.createTags(
                        "reason" to "all_deliveries_failed",
                        "bot_id" to botId,
                        "user_id" to targetUserId
                    )
                    MetricsService.incrementMessageForwardFailures(failureTags)

                    sendBotResponse(
                        connectionId,
                        "error",
                        "Failed to deliver message to any active sessions",
                        targetUserId
                    )
                }
            } catch (e: kotlinx.serialization.SerializationException) {
                // Track message processing error
                val errorTags = MetricsService.createTags(
                    "error_type" to "serialization",
                    "bot_id" to botId
                )
                MetricsService.incrementMessageProcessingErrors(errorTags)

                MDC.put("event_type", "message_validation_error")
                MDC.put("connection_id", connectionId)
                MDC.put("bot_id", botId)
                MDC.put("reason", "JSON deserialization failed")
                logger.error("Error processing bot message: SerializationException", e)
                MDC.clear()
                sendBotResponse(connectionId, "error", "Invalid message format: ${e.message}")
            } catch (e: Exception) {
                // Track message processing error
                val errorTags = MetricsService.createTags(
                    "error_type" to "unexpected",
                    "bot_id" to botId
                )
                MetricsService.incrementMessageProcessingErrors(errorTags)
                MetricsService.incrementApplicationErrors(errorTags)

                MDC.put("event_type", "message_processing_error")
                MDC.put("connection_id", connectionId)
                MDC.put("bot_id", botId)
                logger.error("Unexpected error processing bot message", e)
                MDC.clear()
                sendBotResponse(connectionId, "error", "Error processing message: ${e.message}")
            }
        }

    private suspend fun sendBotResponse(
        connectionId: String, 
        status: String, 
        message: String, 
        userId: String? = null
    ) {
        val session = activeConnections[connectionId] ?: run {
            MDC.put("event_type", "bot_response_failed")
            MDC.put("connection_id", connectionId)
            MDC.put("reason", "Bot connection not found")
            MDC.put("status", status)
            MDC.put("user_id", userId)
            logger.warn("Cannot send response to bot - connection not found")
            MDC.clear()
            return
        }

        val response = BotResponse(status, message, userId)
        try {
            session.outgoing.send(Frame.Text(Json.encodeToString(response)))
            MDC.put("event_type", "bot_response_sent")
            MDC.put("connection_id", connectionId)
            MDC.put("status", status)
            MDC.put("user_id", userId)
            logger.debug("Bot response sent")
            MDC.clear()
        } catch (e: Exception) {
            MDC.put("event_type", "bot_response_failed")
            MDC.put("connection_id", connectionId)
            MDC.put("status", status)
            MDC.put("user_id", userId)
            MDC.put("reason", "Exception during send")
            logger.error("Failed to send response to bot", e)
            MDC.clear()
        }
    }

    private fun removeUserSession(userId: String, connectionId: String) {
        userSessions[userId]?.remove(connectionId)
        if (userSessions[userId]?.isEmpty() == true) {
            userSessions.remove(userId)
        }
        MDC.put("event_type", "session_removed_user")
        MDC.put("connection_id", connectionId)
        MDC.put("user_id", userId)
        logger.debug("Removed user session association")
        MDC.clear()
    }

    private fun removeBotSession(botId: String, connectionId: String) {
        botSessions[botId]?.remove(connectionId)
        if (botSessions[botId]?.isEmpty() == true) {
            botSessions.remove(botId)
        }
        MDC.put("event_type", "session_removed_bot")
        MDC.put("connection_id", connectionId)
        MDC.put("bot_id", botId)
        logger.debug("Removed bot session association")
        MDC.clear()
    }

    fun getActiveSessionsForUser(userId: String): List<String> {
        return userSessions[userId]?.toList() ?: emptyList()
    }

    private fun verifySessions() {
        val zombieUserSessions = mutableListOf<Pair<String, String>>()
        userSessions.forEach { (userId, sessions) ->
            sessions.removeIf { sessionId ->
                 val isZombie = !activeConnections.containsKey(sessionId)
                 if (isZombie) zombieUserSessions.add(userId to sessionId)
                 isZombie
            }
            if (sessions.isEmpty()) {
                userSessions.remove(userId)
            }
        }

        // Track zombie user sessions
        if (zombieUserSessions.isNotEmpty()) {
            val tags = MetricsService.createTags("session_type" to "user")
            MetricsService.incrementZombieSessionsDetected(tags)
        }

        zombieUserSessions.forEach { (userId, sessionId) ->
            MDC.put("event_type", "session_integrity_warning")
            MDC.put("session_type", "user")
            MDC.put("connection_id", sessionId)
            MDC.put("user_id", userId)
            MDC.put("reason", "Associated connection no longer active")
            logger.warn("Cleaning up zombie user session")
            MDC.clear()
        }

        val zombieBotSessions = mutableListOf<Pair<String, String>>()
        botSessions.forEach { (botId, sessions) ->
             sessions.removeIf { sessionId ->
                 val isZombie = !activeConnections.containsKey(sessionId)
                 if (isZombie) zombieBotSessions.add(botId to sessionId)
                 isZombie
             }
            if (sessions.isEmpty()) {
                botSessions.remove(botId)
            }
        }

        // Track zombie bot sessions
        if (zombieBotSessions.isNotEmpty()) {
            val tags = MetricsService.createTags("session_type" to "bot")
            MetricsService.incrementZombieSessionsDetected(tags)
        }

        zombieBotSessions.forEach { (botId, sessionId) ->
            MDC.put("event_type", "session_integrity_warning")
            MDC.put("session_type", "bot")
            MDC.put("connection_id", sessionId)
            MDC.put("bot_id", botId)
            MDC.put("reason", "Associated connection no longer active")
            logger.warn("Cleaning up zombie bot session")
            MDC.clear()
        }
    }

    private suspend fun registerConnection(
        connectionId: String, session: WebSocketSession, userId: String
    ) = withContext(dispatcherProvider.websocket) {
        synchronized(activeConnections) {
            activeConnections[connectionId] = session
            connectionTimestamps[connectionId] = System.currentTimeMillis()
            connectionMetrics.computeIfAbsent(connectionId) { ConnectionMetrics() }
        }

        // Track connection in metrics
        val connectionType = if (connectionId.startsWith("bot_")) "bot" else "user"
        val tags = MetricsService.createTags("connection_type" to connectionType)
        MetricsService.incrementConnectionCount(tags)

        if (connectionId.startsWith("bot_")) {
             storeBotSession(extractBotId(connectionId)!!, connectionId)
        } else {
             storeUserSession(userId, connectionId)
        }

        MDC.put("event_type", "connection_established")
        MDC.put("connection_id", connectionId)
        MDC.put("user_id", userId)
        logger.info("New connection registered")
        MDC.clear()
    }

    private fun verifySessionIntegrity() {
        activeConnections.keys.forEach { connectionId ->
            val isUserSession = userSessions.values.any { it.contains(connectionId) }
            val isBotSession = botSessions.values.any { it.contains(connectionId) }

            if (!isUserSession && !isBotSession) {
                // Track connection integrity errors
                MetricsService.incrementConnectionIntegrityErrors()

                MDC.put("event_type", "connection_integrity_error")
                MDC.put("connection_id", connectionId)
                MDC.put("reason", "Active connection has no corresponding user/bot session record")
                logger.error("Orphaned connection detected - no session association")
                MDC.clear()
                scope.launch { cleanupConnection(connectionId, "integrity_error" to "Orphaned connection detected") }
            }
        }
    }

    private suspend fun processIncomingMessages(connectionId: String, userId: String) {
        val session = activeConnections[connectionId] ?: return

        for (frame in session.incoming) {
            when (frame) {
                is Frame.Pong -> {
                    updateActivityTimestamp(connectionId)
                    val pingId = String(frame.data)
                    pendingPings[pingId]?.let { deferred ->
                        deferred.complete(Unit)
                        pendingPings.remove(pingId)
                        MDC.put("event_type", "keepalive_pong")
                        MDC.put("connection_id", connectionId)
                        MDC.put("user_id", userId)
                        MDC.put("ping_id", pingId)
                        logger.info("Pong received for custom ping")
                        MDC.clear()
                    } ?: run {
                         MDC.put("event_type", "keepalive_pong")
                         MDC.put("connection_id", connectionId)
                         MDC.put("user_id", userId)
                         MDC.put("ping_id", "system")
                         logger.debug("System pong received")
                         MDC.clear()
                    }
                }

                is Frame.Ping -> {
                    updateActivityTimestamp(connectionId)
                    val pingId = String(frame.data)
                    MDC.put("event_type", "keepalive_ping")
                    MDC.put("connection_id", connectionId)
                    MDC.put("user_id", userId)
                    MDC.put("ping_id", pingId)
                    logger.info("Ping received")
                    MDC.clear()
                    try {
                         session.outgoing.send(Frame.Pong(frame.data))
                    } catch (e: Exception) {
                        MDC.put("event_type", "keepalive_pong_failed")
                        MDC.put("connection_id", connectionId)
                        MDC.put("user_id", userId)
                        logger.warn("Failed to send Pong response", e)
                        MDC.clear()
                    }
                }

                is Frame.Text -> {
                    updateActivityTimestamp(connectionId)
                    if (connectionId.startsWith("bot_")) {
                        handleBotMessage(connectionId, frame, userId)
                    } else {
                        handleClientMessage(connectionId, frame, userId)
                    }
                }

                is Frame.Binary -> {
                    updateActivityTimestamp(connectionId)
                    val messagePreview = try { frame.readBytes().decodeToString().take(50) } catch (e: Exception) { "<binary data>" }
                    MDC.put("event_type", "message_received_client")
                    MDC.put("connection_id", connectionId)
                    MDC.put("user_id", userId)
                    MDC.put("message_type", "binary")
                    MDC.put("message_preview", messagePreview)
                    logger.info("Received binary message")
                    MDC.clear()
                }

                is Frame.Close -> {
                    val reason = frame.readReason()
                    val (status, reasonText) = determineCloseStatus(reason)
                    MDC.put("event_type", "connection_close_frame_received")
                    MDC.put("connection_id", connectionId)
                    MDC.put("user_id", userId)
                    MDC.put("reason", reasonText)
                    MDC.put("status", status)
                    MDC.put("close_code", reason?.code?.toString() ?: "N/A")
                    logger.info("Close frame received")
                    MDC.clear()
                    session.close(reason ?: CloseReason(CloseReason.Codes.NORMAL, "Client initiated close"))
                    return
                }
            }
        }
    }

    private suspend fun handleClientMessage(connectionId: String, frame: Frame.Text, userId: String) =
        withContext(dispatcherProvider.websocket) {
            val text = frame.readText()
            val messagePreview = text.take(50)

            MDC.put("event_type", "message_received_client")
            MDC.put("connection_id", connectionId)
            MDC.put("user_id", userId)
            MDC.put("message_type", "text")
            MDC.put("message_preview", messagePreview)
            logger.debug("Received client message")
            MDC.clear()
        }

    internal suspend fun sendMessageToClient(connectionId: String, message: String, userId: String) =
        withContext(dispatcherProvider.websocket) {
            val session = activeConnections[connectionId]
            if (session == null) {
                // Track message send failure
                val failureTags = MetricsService.createTags(
                    "reason" to "connection_not_found",
                    "user_id" to userId
                )
                MetricsService.incrementMessageForwardFailures(failureTags)

                MDC.put("event_type", "message_sent_client_failed")
                MDC.put("connection_id", connectionId)
                MDC.put("user_id", userId)
                MDC.put("reason", "Attempted send to closed/unknown connection")
                MDC.put("status", "connection_not_found")
                logger.warn("Attempted send to closed connection")
                MDC.clear()
                throw IllegalStateException("Connection $connectionId not found")
            }

            try {
                session.outgoing.send(Frame.Text(message))
                updateActivityTimestamp(connectionId)

                // Track message sent
                val tags = MetricsService.createTags(
                    "user_id" to userId
                )
                MetricsService.incrementMessagesSent(tags)

                MDC.put("event_type", "message_sent_client")
                MDC.put("connection_id", connectionId)
                MDC.put("user_id", userId)
                MDC.put("status", "success")
                logger.info("Successfully sent message to client")
                MDC.clear()
            } catch (e: ClosedSendChannelException) {
                // Track message send failure
                val failureTags = MetricsService.createTags(
                    "reason" to "channel_closed",
                    "user_id" to userId
                )
                MetricsService.incrementMessageForwardFailures(failureTags)

                MDC.put("event_type", "message_sent_client_failed")
                MDC.put("connection_id", connectionId)
                MDC.put("user_id", userId)
                MDC.put("reason", "Send channel closed")
                MDC.put("status", "channel_closed")
                logger.warn("Send failed - channel closed", e)
                MDC.clear()
                throw e
            } catch (e: Exception) {
                // Track message send failure
                val failureTags = MetricsService.createTags(
                    "reason" to "unexpected_error",
                    "user_id" to userId
                )
                MetricsService.incrementMessageForwardFailures(failureTags)
                MetricsService.incrementApplicationErrors(failureTags)

                MDC.put("event_type", "message_sent_client_failed")
                MDC.put("connection_id", connectionId)
                MDC.put("user_id", userId)
                MDC.put("reason", "Unexpected send exception")
                MDC.put("status", "error")
                logger.error("Unexpected error sending message to client", e)
                MDC.clear()
                throw e
            }
        }

    internal fun updateActivityTimestamp(connectionId: String) {
        connectionTimestamps[connectionId] = System.currentTimeMillis()
        connectionMetrics[connectionId]?.updateLastActivity()
    }

    /**
     * Gets the last activity timestamp for a connection
     * @param connectionId The ID of the connection
     * @return The timestamp of the last activity, or 0 if not found
     */
    fun getLastActivityTime(connectionId: String): Long {
        return connectionTimestamps[connectionId] ?: 0L
    }

    internal fun extractUserId(connectionId: String): String? {
        return connectionId.split("_").firstOrNull()?.takeIf { it.isNotBlank() && !it.equals("bot", ignoreCase = true) } ?: run {
             if (!connectionId.startsWith("bot_")) {
                 MDC.put("event_type", "connection_id_format_error")
                 MDC.put("connection_id", connectionId)
                 logger.error("Invalid connection ID format: Cannot extract User ID")
                 MDC.clear()
             }
             null
        }
    }

    internal fun extractBotId(connectionId: String): String? {
        val parts = connectionId.split("_")
        return if (parts.size >= 2 && parts[0].equals("bot", ignoreCase = true) && parts[1].isNotBlank()) {
             parts[1]
        } else {
             null
        }
    }

    internal suspend fun cleanupConnection(connectionId: String, closeStatus: Pair<String, String>? = null) = withContext(dispatcherProvider.websocket) {
        val effectiveCloseStatus = closeStatus ?: ("unknown_cleanup" to "Connection cleaned up due to internal trigger")
        val startTime = connectionTimestamps[connectionId]
        val durationMs = startTime?.let { System.currentTimeMillis() - it }

        synchronized(activeConnections) {
            val session = activeConnections.remove(connectionId)
            connectionTimestamps.remove(connectionId)
            connectionMetrics.remove(connectionId)

            val userId = extractUserId(connectionId)
            val botId = extractBotId(connectionId)

            // Track connection metrics
            val connectionType = if (botId != null) "bot" else "user"
            val statusTag = effectiveCloseStatus.first
            val tags = MetricsService.createTags(
                "connection_type" to connectionType,
                "close_status" to statusTag
            )

            // Record connection duration
            durationMs?.let { 
                MetricsService.recordConnectionDuration(it, tags)
            }

            if (userId != null) {
                removeUserSession(userId, connectionId)
            }
            if (botId != null) {
                removeBotSession(botId, connectionId)
            }

            MDC.put("event_type", "connection_terminated")
            MDC.put("connection_id", connectionId)
            userId?.let { MDC.put("user_id", it) }
            botId?.let { MDC.put("bot_id", it) }
            MDC.put("reason", effectiveCloseStatus.second)
            MDC.put("status", effectiveCloseStatus.first)
            durationMs?.let { MDC.put("duration_ms", it.toString()) }
            logger.info("Connection terminated and cleaned up")
            MDC.clear()
        }
    }

    internal suspend fun closeAndCleanupConnection(connectionId: String, session: WebSocketSession, statusCode: CloseReason.Codes = CloseReason.Codes.NORMAL, message: String = "Closing connection") {
        val reason = CloseReason(statusCode, message)
        val closeStatus = determineCloseStatus(reason)
        try {
            MDC.put("event_type", "connection_close_initiated")
            MDC.put("connection_id", connectionId)
            MDC.put("reason", closeStatus.second)
            MDC.put("status", closeStatus.first)
            MDC.put("close_code", statusCode.toString())
            logger.info("Initiating connection close")
            MDC.clear()
            session.close(reason)
        } catch (e: Exception) {
            MDC.put("event_type", "connection_close_error")
            MDC.put("connection_id", connectionId)
            logger.error("Error during session.close()", e)
            MDC.clear()
        } finally {
            cleanupConnection(connectionId, closeStatus)
        }
    }

    fun monitorConnections() {
        scope.launch {
            while (isActive) {
                try {
                     activeConnections.forEach { (connectionId, session) ->
                         val lastActive = connectionTimestamps[connectionId] ?: 0L
                         val inactivityMillis = System.currentTimeMillis() - lastActive
                         val threshold = Config.getInactivityThreshold() * 1000

                         if (inactivityMillis > threshold) {
                             MDC.put("event_type", "connection_inactivity_detected")
                             MDC.put("connection_id", connectionId)
                             MDC.put("inactive_duration_ms", inactivityMillis.toString())
                             MDC.put("threshold_ms", threshold.toString())
                             logger.debug("Connection potentially inactive, sending health ping")
                             MDC.clear()
                             scope.launch { sendHealthPing(connectionId, session) }
                         }
                     }
                } catch (e: Exception) {
                    MDC.put("event_type", "connection_monitor_error")
                    logger.error("Error during connection monitoring loop", e)
                    MDC.clear()
                }
                delay(Config.getHealthCheckInterval().seconds)
            }
        }
    }

    private suspend fun sendHealthPing(connectionId: String, session: WebSocketSession) {
        val pingId = "ping-${UUID.randomUUID()}"
        val pingDeferred = CompletableDeferred<Unit>().also {
            pendingPings[pingId] = it
        }
        val pingStartTime = System.currentTimeMillis()

        try {
            MDC.put("event_type", "keepalive_ping_sent")
            MDC.put("connection_id", connectionId)
            MDC.put("ping_id", pingId)
            logger.debug("Sending health ping")
            MDC.clear()
            session.outgoing.send(Frame.Ping(pingId.toByteArray()))

            withTimeoutOrNull(Config.getPongTimeout().seconds) {
                pingDeferred.await()

                // Calculate and record ping-pong latency
                val latencyMs = System.currentTimeMillis() - pingStartTime
                val connectionType = if (connectionId.startsWith("bot_")) "bot" else "user"
                val tags = MetricsService.createTags("connection_type" to connectionType)
                MetricsService.recordPingPongLatency(latencyMs, tags)

            } ?: run {
                // Track ping timeout
                val connectionType = if (connectionId.startsWith("bot_")) "bot" else "user"
                val tags = MetricsService.createTags("connection_type" to connectionType)
                MetricsService.incrementPingTimeouts(tags)

                MDC.put("event_type", "keepalive_timeout")
                MDC.put("connection_id", connectionId)
                MDC.put("ping_id", pingId)
                MDC.put("timeout_seconds", Config.getPongTimeout().toString())
                logger.warn("Ping timeout, closing connection")
                MDC.clear()
                closeAndCleanupConnection(connectionId, session, CloseReason.Codes.GOING_AWAY, "Ping timeout")
            }
        } catch (e: Exception) {
            // Track ping failure
            MetricsService.incrementConnectionErrors(
                MetricsService.createTags(
                    "error_type" to "ping_failed",
                    "connection_type" to if (connectionId.startsWith("bot_")) "bot" else "user"
                )
            )

            MDC.put("event_type", "keepalive_ping_failed")
            MDC.put("connection_id", connectionId)
            MDC.put("ping_id", pingId)
            logger.error("Ping failed, closing connection", e)
            MDC.clear()
            closeAndCleanupConnection(connectionId, session, INTERNAL_ERROR, "Ping failed: ${e.message}")
        } finally {
            pendingPings.remove(pingId)
        }
    }

    private fun logConnectionStats() {
        val activeConnectionsCount = activeConnections.size
        val userSessionCount = userSessions.size
        val botSessionCount = botSessions.size

        // Update metrics
        MetricsService.updateActiveConnections(
            total = activeConnectionsCount,
            users = userSessions.values.sumOf { it.size },
            bots = botSessions.values.sumOf { it.size }
        )
        MetricsService.updateActiveUsers(userSessionCount)
        MetricsService.updateActiveBots(botSessionCount)

        // Log the stats
        MDC.put("event_type", "connection_stats")
        MDC.put("active_connections", activeConnectionsCount.toString())
        MDC.put("connection_metrics_size", connectionMetrics.size.toString())
        MDC.put("connection_timestamps_size", connectionTimestamps.size.toString())
        MDC.put("user_session_count", userSessionCount.toString())
        MDC.put("bot_session_count", botSessionCount.toString())
        logger.info("Periodic connection statistics")
        MDC.clear()
    }
}
