package de.yuuto.autoOpener.util

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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WebSocketManager(private val dispatcherProvider: DispatcherProvider) {
    private val logger = LoggerFactory.getLogger(WebSocketManager::class.java)
    internal val activeConnections = ConcurrentHashMap<String, WebSocketSession>()
    internal val connectionTimestamps = ConcurrentHashMap<String, Long>()
    internal val connectionMetrics = ConcurrentHashMap<String, ConnectionMetrics>()

    private lateinit var redis: RedisManager

    init {
        CoroutineScope(dispatcherProvider.monitoring).launch {
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

    private val connectionTimeout = 2.minutes

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

    private suspend fun processIncomingMessages(
        connectionId: String, userId: String
    ) {
        val topic = redis.getTopic(userId)
        var listenerId = -1

        try {
            listenerId = topic.addListener(String::class.java) { _, message ->
                CoroutineScope(dispatcherProvider.websocket).launch {
                    handleIncomingMessage(connectionId, userId, message)
                }
            }

            for (frame in activeConnections[connectionId]?.incoming ?: return) {
                when (frame) {
                    is Frame.Pong -> {
                        updateActivityTimestamp(connectionId)
                        logger.info("[$connectionId] | $userId Pong received")
                    }
                    is Frame.Text -> handleClientMessage(connectionId, frame)
                    else -> logger.info("[$connectionId] | $userId Unhandled frame type")
                }
            }
        } finally {
            topic.removeListener(listenerId)
        }
    }

    internal suspend fun handleIncomingMessage(
        connectionId: String, userId: String, message: String
    ) {
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
        }

    private suspend fun sendMessageToClient(connectionId: String, message: String, userId: String) =
        withContext(dispatcherProvider.websocket) {
            val session = activeConnections[connectionId] ?: run {
                logger.warn("[$connectionId] | $userId Attempted send to closed connection")
                throw IllegalStateException("Connection closed")
            }

            try {
                session.outgoing.send(Frame.Text(message))
                updateActivityTimestamp(connectionId)
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
                // First, clean up local resources without locking
                activeConnections.remove(connectionId)
                connectionTimestamps.remove(connectionId)
                connectionMetrics.remove(connectionId)

                // Then handle Redis cleanup with proper lock handling
                val lockKey = "connection-cleanup:$connectionId"
                val lock = redis.client.getLock(lockKey)

                try {
                    // Use a short timeout for lock acquisition
                    if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
                        try {
                            // Remove from Redis
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

    private suspend fun closeAndCleanupConnection(connectionId: String, session: WebSocketSession) {
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
        CoroutineScope(dispatcherProvider.monitoring).launch {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    activeConnections.forEach { (connectionId, session) ->
                        // Check last activity timestamp
                        if (now - connectionTimestamps.getOrDefault(connectionId, 0) > connectionTimeout.inWholeMilliseconds) {
                            logger.warn("[$connectionId] Inactive connection terminated")
                            closeAndCleanupConnection(connectionId = connectionId, session = session)
                        } else {
                            // Send ping to verify connection is still alive
                            try {
                                val pingSuccess = CompletableDeferred<Boolean>()
                                val pingStart = System.currentTimeMillis()

                                session.outgoing.send(Frame.Ping("ping-${UUID.randomUUID()}".toByteArray()))

                                // Wait for pong with timeout
                                withTimeoutOrNull(Config.getPongTimeout()) {
                                    // This would be triggered by pong handler
                                    pingSuccess.await()
                                } ?: run {
                                    logger.warn("[$connectionId] Ping timeout - terminating connection")
                                    closeAndCleanupConnection(connectionId, session)
                                }
                            } catch (e: Exception) {
                                logger.error("[$connectionId] Error during ping: ${e.message}")
                                closeAndCleanupConnection(connectionId, session)
                            }
                        }
                    }
                    logger.info("")
                } catch (e: Exception) {
                    logger.error("Error in connection monitoring: ${e.message}", e)
                } finally {
                    delay(30.seconds)
                }
            }
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
