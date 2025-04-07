package de.yuuto.autoOpener.util

import com.github.benmanes.caffeine.cache.Caffeine
import de.yuuto.autoOpener.dataclass.ConnectionMetrics
import de.yuuto.autoOpener.dataclass.WebSocketMessage
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
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
    private val processedMessages = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build<String, Boolean>()

    private lateinit var redis: RedisManager

    init {
        scope.launch {
            while (isActive) {
                logConnectionStats()
                delay(60.seconds)
                logger.info("Current active connections: ${activeConnections.size} | " +
                        "Connection metrics: ${connectionMetrics.size} | " +
                        "Connection timestamps: ${connectionTimestamps.size}")
            }
        }
    }

    fun setRedisManager(redisManager: RedisManager) {
        this.redis = redisManager
    }

    internal fun getSessionById(connectionId: String): WebSocketSession? {
        return activeConnections[connectionId]
    }

    fun shutdown() {
        activeConnections.clear()
        connectionTimestamps.clear()
        connectionMetrics.clear()

        logger.info("WebSocketManager resources released")
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
            cleanupConnection(connectionId, userId)
        }
    }

    private suspend fun registerConnection(
        connectionId: String, session: WebSocketSession, userId: String
    ) = withContext(dispatcherProvider.websocket) {
        activeConnections[connectionId] = session
        connectionTimestamps[connectionId] = System.currentTimeMillis()
        redis.storeSession(userId, connectionId)
        logger.info("[$connectionId] | $userId New connection registered")
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
                is Frame.Text -> handleClientMessage(connectionId, frame)
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

    private fun updateActivityTimestamp(connectionId: String) {
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


    internal suspend fun cleanupConnection(connectionId: String, userId: String) =
        withContext(dispatcherProvider.websocket) {
            try {
                val lockKey = "connection-cleanup:$userId"
                val lock = redis.client.getLock(lockKey)

                try {
                    // Use a short timeout for lock acquisition
                    if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
                        try {
                            // Remove from Redis
                            activeConnections.remove(connectionId)
                            connectionTimestamps.remove(connectionId)
                            connectionMetrics.remove(connectionId)

                            redis.removeSession(userId, connectionId)
                            redis.stopMonitoringSubscription(userId)
                            logger.info("[$connectionId] | $userId Connection cleaned up")
                        } finally {
                            // Only unlock if we own the lock
                            if (lock.isHeldByCurrentThread()) {
                                lock.unlock()
                            }
                        }
                    } else {
                        logger.warn("[$connectionId] | $userId Lock acquisition failed for cleanup")
                    }
                } catch (e: Exception) {
                    logger.error("[$connectionId] Error during Redis cleanup: ${e.message}", e)
                    // Still ensure we release the lock if we hold it
                    if (lock.isHeldByCurrentThread()) {
                        try {
                            lock.unlock()
                        } catch (e: Exception) {
                            logger.error("[$connectionId] Error unlocking: ${e.message}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("[$connectionId] Error during connection cleanup: ${e.message}", e)
            }
        }

    internal suspend fun closeAndCleanupConnection(connectionId: String, session: WebSocketSession) {
        try {
            session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Connection timeout"))
        } catch (e: Exception) {
            logger.error("[$connectionId] Error closing connection: ${e.message}")
        } finally {
            val userId = extractUserId(connectionId)
            cleanupConnection(connectionId, userId)
        }
    }

    fun monitorConnections() {
        scope.launch {
            while (isActive) {
                val currentTime = System.currentTimeMillis()
                val inactivityThreshold = Config.getInactivityThreshold()
                
                activeConnections.forEach { (connectionId, session) ->
                    val lastActivity = connectionTimestamps[connectionId] ?: 0
                    val inactiveDuration = currentTime - lastActivity
                    
                    if (inactiveDuration > inactivityThreshold) {
                        val pingId = "ping-${UUID.randomUUID()}"
                        sendPing(connectionId, session, pingId)
                    }
                }
                delay(30.seconds)
            }
        }
    }

    private suspend fun sendPing(connectionId: String, session: WebSocketSession, pingId: String) {
        val pingDeferred = CompletableDeferred<Unit>().also {
            pendingPings[pingId] = it
        }
        logger.info("[$connectionId] Ping: $pingId")
        try {
            session.outgoing.send(Frame.Ping(pingId.toByteArray()))
            withTimeoutOrNull((Config.getPongTimeout()) * 1000L) {
                pingDeferred.await()
            } ?: run {
                logger.warn("[$connectionId] Ping timeout - terminating")
                closeAndCleanupConnection(connectionId, session)
            }
        } catch (e: Exception) {
            logger.error("[$connectionId] Ping error: ${e.message}")
            closeAndCleanupConnection(connectionId, session)
        } finally {
            pendingPings.remove(pingId)
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

