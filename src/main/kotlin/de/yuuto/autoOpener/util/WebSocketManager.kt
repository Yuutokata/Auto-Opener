package de.yuuto.autoOpener.util

import de.yuuto.autoOpener.dataclass.WebSocketMessage
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WebSocketManager(private val redis: RedisManager) {
    private val logger = LoggerFactory.getLogger(WebSocketManager::class.java)
    private val activeConnections = ConcurrentHashMap<String, WebSocketSession>()
    private val connectionTimestamps = ConcurrentHashMap<String, Long>()
    private val heartbeatIntervals = ConcurrentHashMap<String, Job>()

    private val pingInterval = 25.seconds
    private val connectionTimeout = 2.minutes
    private val maxRetryAttempts = 5
    private val backoffBase = 1000L

    suspend fun handleSession(session: WebSocketSession, userId: String) {
        val connectionId = "${userId}_${UUID.randomUUID()}"
        val retryCounter = AtomicInteger(0)

        try {
            registerConnection(connectionId, session, userId)
            startHeartbeat(connectionId, session, userId)
            processIncomingMessages(connectionId, userId, retryCounter)
        } catch (e: ClosedReceiveChannelException) {
            logger.info("[$connectionId] Graceful disconnect")
        } catch (e: IOException) {
            if (e.message?.contains("Ping timeout") == true) {
                logger.info("[$connectionId] | $userId Client ping timeout - assuming disconnected")
            } else {
                logger.error("[$connectionId] | $userId IO error: ${e.message}", e)
            }
        } catch (e: Exception) {
            logger.error("[$connectionId] | $userId Unexpected error: ${e.message}", e)
        } finally {
            cleanupConnection(connectionId, userId)
        }
    }

    private fun registerConnection(
        connectionId: String, session: WebSocketSession, userId: String
    ) {
        activeConnections[connectionId] = session
        connectionTimestamps[connectionId] = System.currentTimeMillis()
        redis.storeSession(userId, connectionId)
        logger.info("[$connectionId] | $userId New connection registered")
    }

    private fun startHeartbeat(connectionId: String, session: WebSocketSession, userId: String) {
        heartbeatIntervals[connectionId] = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    session.outgoing.send(Frame.Ping(ByteArray(0)))
                    delay(pingInterval)
                } catch (e: IOException) {
                    if (e.message?.contains("Ping timeout") == true) {
                        logger.info("[$connectionId] | $userId Ping timeout detected")
                    } else {
                        logger.debug("[$connectionId] | $userId IO error in heartbeat: ${e.message}")
                    }
                    cancel()
                    break
                } catch (e: Exception) {
                    logger.debug("[$connectionId] | $userId Heartbeat failed: ${e.message}")
                    cancel()
                    break
                }
            }
        }
    }

    private suspend fun processIncomingMessages(
        connectionId: String, userId: String, retryCounter: AtomicInteger
    ) {
        val topic = redis.getTopic(userId)
        var listenerId = -1

        try {
            listenerId = topic.addListener(String::class.java) { _, message ->
                CoroutineScope(Dispatchers.IO).launch {
                    handleIncomingMessage(connectionId, userId, message, retryCounter)
                }
            }

            for (frame in activeConnections[connectionId]?.incoming ?: return) {
                when (frame) {
                    is Frame.Pong -> updateActivityTimestamp(connectionId)
                    is Frame.Text -> handleClientMessage(connectionId, frame)
                    else -> logger.debug("[$connectionId] | $userId Unhandled frame type")
                }
            }
        } finally {
            topic.removeListener(listenerId)
        }
    }

    private suspend fun handleIncomingMessage(
        connectionId: String, userId: String, message: String, retryCounter: AtomicInteger
    ) {
        if (!validateMessageFormat(message)) {
            logger.error("[$connectionId] | $userId Invalid message format: ${message.take(50)}")
            return
        }

        withRetry(retryCounter, userId) {
            sendMessageToClient(
                connectionId, Json.encodeToString(WebSocketMessage("Keyword Ping", message)), userId
            )
            logger.debug("[$connectionId] | $userId Message forwarded successfully")
        }
    }

    private suspend fun withRetry(
        retryCounter: AtomicInteger, userId: String, block: suspend () -> Unit
    ) {
        try {
            block()
            retryCounter.set(0)
        } catch (e: Exception) {
            if (retryCounter.get() >= maxRetryAttempts) {
                logger.error("Max retries reached for message delivery to ${userId}: ${e.message}")
                throw e
            }
            val delay = backoffBase * 2.0.pow(retryCounter.getAndIncrement().toDouble()).toLong()
            delay(delay)
            withRetry(retryCounter, userId, block)
        }
    }

    private fun handleClientMessage(connectionId: String, frame: Frame.Text) {
        val text = frame.readText()
        logger.debug("[$connectionId] Received client message: ${text.take(50)}")
    }

    private suspend fun sendMessageToClient(connectionId: String, message: String, userId: String) {
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

    private fun validateMessageFormat(message: String): Boolean {
        return message.run {
            length < 2048 && matches(Regex("^https?://([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,6}(/\\S*)?$"))
        }
    }

    private fun cleanupConnection(connectionId: String, userId: String) {
        heartbeatIntervals[connectionId]?.cancel()
        activeConnections.remove(connectionId)
        connectionTimestamps.remove(connectionId)
        redis.removeSession(userId, connectionId)
        logger.info("[$connectionId] | $userId Connection cleaned up")
    }

    fun monitorConnections() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val now = System.currentTimeMillis()
                connectionTimestamps.forEach { (connectionId, timestamp) ->
                    if (now - timestamp > connectionTimeout.inWholeMilliseconds) {
                        logger.warn("[$connectionId] Inactive connection terminated")
                        activeConnections[connectionId]?.close(
                            CloseReason(
                                CloseReason.Codes.VIOLATED_POLICY, "Connection timeout"
                            )
                        )
                    }
                }
                delay(30.seconds)
            }
        }
    }
}