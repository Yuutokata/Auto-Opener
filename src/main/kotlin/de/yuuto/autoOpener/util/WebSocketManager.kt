package de.yuuto.autoOpener.util

import com.github.benmanes.caffeine.cache.Caffeine
import de.yuuto.autoOpener.dataclass.ConnectionMetrics
import de.yuuto.autoOpener.dataclass.WebSocketMessage
import de.yuuto.autoOpener.dataclass.WebsocketReceive
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class WebSocketManager(private val dispatcherProvider: DispatcherProvider) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcherProvider.monitoring + supervisorJob)
    private val logger = LoggerFactory.getLogger(WebSocketManager::class.java)
    internal val activeConnections = ConcurrentHashMap<String, WebSocketSession>()
    internal val connectionTimestamps = ConcurrentHashMap<String, Long>()
    internal val connectionMetrics = ConcurrentHashMap<String, ConnectionMetrics>()
    private val pendingPings = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val processedMessages = Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build<String, Boolean>()

    private val userSessions = ConcurrentHashMap<String, MutableSet<String>>()
    private val botSessions = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        scope.launch {
            while (isActive) {
                synchronized(activeConnections) { // Ensure thread-safe access
                    logConnectionStats()
                    verifySessionIntegrity()
                }
                verifySessions() // Periodic session verification
                delay(60.seconds)
                synchronized(activeConnections) { // Log after ensuring updates are complete
                    logger.info(
                        "Current active connections: ${activeConnections.size} | " +
                        "Connection metrics: ${connectionMetrics.size} | " +
                        "Connection timestamps: ${connectionTimestamps.size}"
                    )
                }
            }
        }
    }

    fun shutdown() {
        activeConnections.clear()
        connectionTimestamps.clear()
        connectionMetrics.clear()

        logger.info("WebSocketManager resources released")
    }

    private fun storeUserSession(userId: String, connectionId: String) {
        userSessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(connectionId)
        logger.debug("Stored user session $connectionId for $userId")
    }

    fun storeBotSession(botId: String, connectionId: String) {
        botSessions.computeIfAbsent(botId) { ConcurrentHashMap.newKeySet() }.add(connectionId)
        logger.debug("Stored bot session $connectionId")
    }

    suspend fun handleBotSession(session: WebSocketSession, botId: String, connectionId: String) {
        try {
            logger.debug("[CONN|{}] Processing bot session start", connectionId)
            session.send(Frame.Text(Json.encodeToString(mapOf("status" to "connected"))))

            registerBotConnection(connectionId, session, botId)
            delay(100)
            processIncomingMessages(connectionId, botId) // Reuse the message processing logic
        } catch (e: ClosedReceiveChannelException) {
            logger.info("[$connectionId] Bot graceful disconnect")
        } catch (e: IOException) {
            logger.error("[$connectionId] | $botId Bot IO error: ${e.message}", e)
        } catch (e: Exception) {
            logger.error("[$connectionId] | $botId Bot unexpected error: ${e.message}", e)
        } finally {
            cleanupConnection(connectionId)
        }
    }



    private fun registerBotConnection(
        connectionId: String, session: WebSocketSession, botId: String
    ) {
        synchronized(activeConnections) {
            activeConnections[connectionId] = session
            connectionTimestamps[connectionId] = System.currentTimeMillis()
        }
        storeBotSession(botId, connectionId)
        logger.info("[$connectionId] | $botId New bot connection registered")
    }

    fun getActiveSessionsForBot(botId: String): List<String> {
        return botSessions[botId]?.toList() ?: emptyList()
    }

    private fun removeBotSession(botId: String, connectionId: String) {
        botSessions[botId]?.remove(connectionId)
        if (botSessions[botId]?.isEmpty() == true) {
            botSessions.remove(botId)
        }
        logger.debug("Removed session $connectionId for bot $botId")
    }

    suspend fun handleSession(session: WebSocketSession, userId: String, connectionId: String) {
        try {
            registerConnection(connectionId, session, userId)
            processIncomingMessages(connectionId, userId)
        } catch (e: ClosedReceiveChannelException) {
            logger.info("[$connectionId] Graceful disconnect")
        } catch (e: IOException) {
            logger.error("[$connectionId] | $userId IO error: ${e.message}", e)
        } catch (e: Exception) {
            logger.error("[$connectionId] | $userId Unexpected error: ${e.message}", e)
        } finally {
            cleanupConnection(connectionId)
        }
    }

    private suspend fun handleBotMessage(connectionId: String, frame: Frame.Text, botId: String) = withContext(dispatcherProvider.websocket) {
        val text = frame.readText()
        logger.debug("[$connectionId] | $botId Received bot message: ${text.take(50)}")
        try {
            val websocketReceive = Json.decodeFromString<WebsocketReceive>(text)

            if (!websocketReceive.userId.matches(Regex("^\\d{15,20}$"))) {
                logger.error("[$connectionId] | $botId Invalid user ID format: ${websocketReceive.userId}")
                return@withContext
            }
            if (!websocketReceive.userId.matches(Regex("^\\d{15,20}$"))) {
                logger.error("[$connectionId] | $botId Invalid user ID format: ${websocketReceive.userId}")

                return@withContext
            }
            val activeSessions = getActiveSessionsForUser(websocketReceive.userId)
            if (activeSessions.isEmpty()) {
                logger.warn("[$connectionId] | $botId No active sessions found for user ${websocketReceive.userId}")
                return@withContext
            }
            activeSessions.forEach { userConnectionId ->
                try {
                    sendMessageToClient(userConnectionId, Json.encodeToString(websocketReceive.message), websocketReceive.userId)
                    logger.info("[$connectionId] | $botId Message forwarded to user ${websocketReceive.userId}")
                } catch (e: Exception) {
                    logger.error("[$connectionId] | $botId Failed to send message to ${websocketReceive.userId}: ${e.message}")
                }
                updateActivityTimestamp(connectionId)
            }
        } catch (e: Exception) {
            logger.error("[$connectionId] | $botId Error processing bot message: ${e.message}", e)
        }
    }


    private fun storeSession(userId: String, connectionId: String) {
        userSessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(connectionId)
        logger.debug("Stored session $connectionId for user $userId")
    }

    private fun removeSession(userId: String, connectionId: String) {
        userSessions[userId]?.remove(connectionId)
        if (userSessions[userId]?.isEmpty() == true) {
            userSessions.remove(userId)
        }
        logger.debug("Removed session $connectionId for user $userId")
    }

    fun getActiveSessionsForUser(userId: String): List<String> {
        return userSessions[userId]?.toList() ?: emptyList()
    }

    private fun verifySessions() {
        val zombieSessions = mutableListOf<Pair<String, String>>()
        userSessions.forEach { (userId, sessions) ->
            sessions.forEach { sessionId ->
                if (!activeConnections.containsKey(sessionId)) {
                    zombieSessions.add(userId to sessionId)
                }
            }
        }
        zombieSessions.forEach { (userId, sessionId) ->
            logger.warn("Cleaning up zombie session: $sessionId for user $userId")
            removeSession(userId, sessionId)
        }
    }

    private suspend fun registerConnection(
        connectionId: String, session: WebSocketSession, userId: String
    ) = withContext(dispatcherProvider.websocket) {
        synchronized(activeConnections) { // Ensure thread-safe updates
            activeConnections[connectionId] = session
            connectionTimestamps[connectionId] = System.currentTimeMillis()
        }
        storeSession(userId, connectionId) // Store session in memory
        logger.info("[$connectionId] | $userId New connection registered")
    }

    private fun verifySessionIntegrity() {
        activeConnections.keys.forEach { connectionId ->
            if (!userSessions.values.any { it.contains(connectionId) } &&
                !botSessions.values.any { it.contains(connectionId) }
            ) {
                logger.error("[$connectionId] Orphaned connection detected")
                scope.launch { cleanupConnection(connectionId) }
            }
        }
    }

    private suspend fun processIncomingMessages(connectionId: String, userId: String) {
        val session = activeConnections[connectionId] ?: return

        for (frame in session.incoming) {
            logger.debug("[{}] | {} Received frame: {}", connectionId, userId, frame)
            when (frame) {
                is Frame.Pong -> {
                    // Update activity timestamp for any pong frame
                    updateActivityTimestamp(connectionId)

                    // Handle custom ping tracking
                    val pingId = String(frame.data)
                    pendingPings[pingId]?.let { deferred ->
                        deferred.complete(Unit)
                        pendingPings.remove(pingId)
                        logger.info("[$connectionId] | $userId Pong received for $pingId")
                    } ?: logger.debug("[$connectionId] | $userId System pong received")
                }

                is Frame.Ping -> {
                    // Manually respond to ping frames in raw WebSocket mode
                    val pingId = String(frame.data)
                    logger.info("[$connectionId] | $userId Ping received: $pingId")
                    // Respond with a pong frame containing the same data
                    session.outgoing.send(Frame.Pong(frame.data))
                    updateActivityTimestamp(connectionId)
                }

                is Frame.Text -> {
                    if (connectionId.startsWith("bot_")) {
                        handleBotMessage(connectionId, frame, userId)
                    } else {
                        handleClientMessage(connectionId, frame)
                    }
                }
                is Frame.Binary -> {
                    val message = frame.readBytes().decodeToString()
                    logger.info("[$connectionId] | $userId Received binary: $message")
                }

                is Frame.Close -> {
                    logger.info("[$connectionId] | $userId Close frame received")
                    closeAndCleanupConnection(connectionId, session)
                }
            }
        }
    }

    internal suspend fun handleIncomingMessage(
        connectionId: String, userId: String, message: String
    ) {
        val messageId = "${message.hashCode()}_${System.currentTimeMillis() / 1000}"
        if (processedMessages.getIfPresent(messageId) == true) {
            logger.warn("[$connectionId] | $userId Duplicate message detected: ${message.take(50)}")
            return
        }
        logger.debug("[$connectionId] | $userId Received message: ${message.take(50)}")
        if (!validateMessageFormat(message)) {
            logger.error("[$connectionId] | $userId Invalid message format: ${message.take(50)}")
            return
        }

        sendMessageToClient(
            connectionId, Json.encodeToString(WebSocketMessage("Keyword Ping", message)), userId
        )
        logger.debug("[$connectionId] | $userId Message forwarded successfully")
    }

    private suspend fun handleClientMessage(connectionId: String, frame: Frame.Text) =
        withContext(dispatcherProvider.websocket) {
            val text = frame.readText()
            logger.debug("[$connectionId] Received client message: ${text.take(50)}")
            updateActivityTimestamp(connectionId) // Update activity timestamp
        }

    private suspend fun sendMessageToClient(connectionId: String, message: String, userId: String) =
        withContext(dispatcherProvider.websocket) {
            val session = activeConnections[connectionId] ?: run {
                logger.warn("[$connectionId] | $userId Attempted send to closed connection")
                throw IllegalStateException("Connection closed")
            }

            try {
                session.outgoing.send(Frame.Text(message))
                updateActivityTimestamp(connectionId) // Update activity timestamp
                logger.debug("[{}] | {} Successfully sent message to client", connectionId, userId)
            } catch (e: ClosedSendChannelException) {
                logger.warn("[$connectionId] | $userId Send failed - channel closed")
                throw e
            }
        }

    internal fun updateActivityTimestamp(connectionId: String) {
        connectionTimestamps[connectionId] = System.currentTimeMillis()
    }

    private suspend fun validateMessageFormat(message: String): Boolean {
        return withContext(dispatcherProvider.processing) {
            message.run {
                length < 2048 && matches(Regex("^https?://([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,6}(/\\S*)?$"))
            }
        }
    }

    internal fun extractUserId(connectionId: String): String {
        return connectionId.split("_").firstOrNull() ?: run {
            logger.error("[$connectionId] Invalid connection ID format")
            "unknown"
        }
    }

    internal suspend fun cleanupConnection(connectionId: String) =
        withContext(dispatcherProvider.websocket) {
            synchronized(activeConnections) {
                activeConnections.remove(connectionId)
                connectionTimestamps.remove(connectionId)
                connectionMetrics.remove(connectionId)

                // Check if this is a user connection
                val userId = extractUserId(connectionId)
                if (connectionId.contains("_")) {
                    if (connectionId.startsWith("bot_")) {
                        val botId = connectionId.split("_")[1]
                        removeBotSession(botId, connectionId)
                    } else {
                        removeSession(userId, connectionId)
                    }
                }

                logger.info("[$connectionId] Connection fully cleaned up")
            }
        }

    internal suspend fun closeAndCleanupConnection(connectionId: String, session: WebSocketSession) {
        try {
            session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Connection timeout"))
        } catch (e: Exception) {
            logger.error("[$connectionId] Error closing connection: ${e.message}")
        } finally {
            val userId = extractUserId(connectionId)
            cleanupConnection(connectionId)
        }
    }

    fun monitorConnections() {
        scope.launch {
            while (isActive) {
                activeConnections.forEach { (connectionId, session) ->
                    val lastActive = connectionTimestamps[connectionId] ?: 0
                    if (System.currentTimeMillis() - lastActive > Config.getInactivityThreshold()) {
                        sendHealthPing(connectionId, session)
                    }
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

        try {
            session.outgoing.send(Frame.Ping(pingId.toByteArray()))
            withTimeoutOrNull(Config.getPongTimeout().seconds) {
                pingDeferred.await()
            } ?: run {
                logger.warn("[$connectionId] Ping timeout")
                closeConnection(connectionId, session)
            }
        } catch (e: Exception) {
            logger.error("[$connectionId] Ping failed: ${e.message}")
            closeConnection(connectionId, session)
        } finally {
            pendingPings.remove(pingId)
        }
    }

    private suspend fun closeConnection(connectionId: String, session: WebSocketSession) {
        try {
            session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Health check failed"))
        } catch (e: Exception) {
            logger.error("[$connectionId] Close error: ${e.message}")
        } finally {
            cleanupConnection(connectionId)
        }
    }

    fun logConnectionStats() {
        CoroutineScope(dispatcherProvider.monitoring).launch {
            if (connectionMetrics.isEmpty()) {
                logger.debug("No connection metrics to report")
                return@launch
            }

            connectionMetrics.forEach { (id, metrics) ->
                val userId = extractUserId(id)
                logger.info(
                    "[METRICS|{}|{}] Latency: {}ms | Invalid responses: {} | Sequence mismatches: {}",
                    id,
                    userId,
                    metrics.networkLatency.get(),
                    metrics.invalidResponses.get(),
                    metrics.sequenceMismatches.get()
                )
            }
        }
    }
}
