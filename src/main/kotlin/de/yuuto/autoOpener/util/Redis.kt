package de.yuuto.autoOpener.util

import kotlinx.coroutines.*
import org.redisson.Redisson
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
import org.redisson.codec.JsonJacksonCodec
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.redisson.config.Config as RedissonConfig

class RedisManager(private val dispatcherProvider: DispatcherProvider, private val webSocketManager: WebSocketManager) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcherProvider.monitoring + supervisorJob)
    private val redisConfig = RedissonConfig().apply {
        useSingleServer().apply {
            address = "redis://${Config.getRedisHost()}:${Config.getRedisPort()}"
            subscriptionConnectionPoolSize = Config.getSubscriptionConnectionPoolSize()
            connectionPoolSize = Config.getConnectionPoolSize()
            connectionMinimumIdleSize = Config.getSubscriptionConnectionMinimumIdleSize()
            subscriptionsPerConnection = Config.getSubscriptionsPerConnection()
        }
        codec = JsonJacksonCodec()
    }
    internal val client: RedissonClient = Redisson.create(redisConfig)
    private val sessionMap = client.getMapCache<String, String>("active_sessions")
    private val logger = LoggerFactory.getLogger(RedisManager::class.java)
    private val dlq = client.getTopic("dead_letter_queue")
    private val subscribedTopics = ConcurrentHashMap<String, RTopic>()

    private val subscriptionStates = ConcurrentHashMap<String, SubscriptionState>()

    data class SubscriptionState(
        var healthCheckJob: Job? = null,
        val created: Long = System.currentTimeMillis(),
        var lastSuccessfulCheck: Long = System.currentTimeMillis(),
        var lastActive: Long = System.currentTimeMillis(),
        var errorCount: Int = 0
    )

    init {
        sessionMap.clear()
        scope.launch {
            while (isActive) {
                subscriptionStates.forEach { (userId, state) ->
                    val lastCheckAgo = (System.currentTimeMillis() - state.lastSuccessfulCheck) / 1000
                    logger.info(
                        "[REDIS|HEALTH|{}] Status: {} | Last check: {}s ago | Errors: {}",
                        userId,
                        if (state.healthCheckJob?.isActive == true) "ACTIVE" else "INACTIVE",
                        lastCheckAgo,
                        state.errorCount
                    )
                }
                delay(5.minutes)
            }
        }
        startSessionVerification()
    }


    suspend fun storeSession(userId: String, connectionId: String) = withContext(dispatcherProvider.network) {
        try {
            val userSessionsKey = "user_sessions:$userId"
            client.getSet<String>(userSessionsKey).add(connectionId)
            logger.debug("Stored session $connectionId for user $userId")
        } catch (e: Exception) {
            logger.error("Failed to store session in Redis: ${e.message}", e)
            throw e
        }
    }

    suspend fun removeSession(userId: String, connectionId: String) = withContext(dispatcherProvider.network) {
        try {
            val userSessionsKey = "user_sessions:$userId"
            client.getSet<String>(userSessionsKey).remove(connectionId)
            logger.debug("Removed session $connectionId for user $userId")
        } catch (e: Exception) {
            logger.error("Failed to remove session from Redis: ${e.message}", e)
            throw e
        }
    }

    fun getTopic(userId: String): RTopic {
        return subscribedTopics.computeIfAbsent("user:$userId") {
            client.getTopic("user:$userId").apply {
                addListener(String::class.java) { _, msg ->
                    logger.debug("[REDIS|{}] Received message: {}", userId, msg.take(200))

                    if (!msg.matches(Regex("^https?://.*"))) {
                        logger.warn("[REDIS|{}] Invalid message format: {}", userId, msg.take(200))
                        dlq.publish("Invalid URL: $msg")
                    }

                    val traceId = UUID.randomUUID().toString()
                    logger.info("[REDIS|{}|{}] Processing started", userId, traceId)
                    try {
                        CoroutineScope(dispatcherProvider.redisSubscriptions).launch {
                            val activeSessions = getActiveSessionsForUser(userId)
                            if (activeSessions.isEmpty()) {
                                logger.warn("[REDIS|{}|{}] No active sessions found", userId, traceId)
                                return@launch
                            }

                            // For each active session, send the message via websocket
                            var deliveredCount = 0
                            for (connectionId in activeSessions) {
                                try {
                                    val session = webSocketManager.getSessionById(connectionId)
                                    if (session != null) {
                                        webSocketManager.handleIncomingMessage(connectionId, userId, msg)
                                        deliveredCount++
                                    } else {
                                        logger.warn(
                                            "[REDIS|{}|{}] Session not found for connection {}",
                                            userId,
                                            traceId,
                                            connectionId
                                        )
                                        removeSession(userId, connectionId)
                                    }
                                } catch (e: Exception) {
                                    logger.error(
                                        "[REDIS|{}|{}] Error delivering to {}: {}",
                                        userId,
                                        traceId,
                                        connectionId,
                                        e.message
                                    )
                                }
                            }

                            logger.info(
                                "[REDIS|{}|{}] Processing completed - delivered to {}/{} clients",
                                userId,
                                traceId,
                                deliveredCount,
                                activeSessions.size
                            )
                        }
                    } catch (e: Exception) {
                        logger.error(
                            "[REDIS|{}|{}] Processing failed: {}", userId, traceId, e.message
                        )
                    }
                }
            }
        }
    }

    suspend fun publish(userId: String, message: String) = withContext(dispatcherProvider.network) {
        try {
            val topic = getTopic(userId)
            topic.publish(message)
            logger.debug("Published message to user $userId")
        } catch (e: Exception) {
            logger.error("Failed to publish to Redis for user $userId: ${e.message}", e)
            throw e
        }
    }

    fun monitorSubscription(userId: String, connectionId: String) {
        subscriptionStates[userId] = SubscriptionState().apply {
            healthCheckJob = scope.launch {
                while (isActive) {
                    try {
                        // 1. Add null-safe access
                        val lastActive = webSocketManager.connectionTimestamps[connectionId] ?: run {
                            logger.warn("[REDIS|HEALTH|$userId] No activity timestamp")
                            val session = webSocketManager.getSessionById(connectionId)
                            if (session != null) {
                                webSocketManager.closeAndCleanupConnection(connectionId, session)
                            } else {
                                logger.warn("[REDIS|HEALTH|$userId] Session not found")
                            }
                            cancel("Connection terminated")
                            return@launch
                        }

                        // 2. Add duration conversion safeguard
                        val inactiveDuration = System.currentTimeMillis() - lastActive

                        // 3. Add connection existence check
                        val session = webSocketManager.activeConnections[connectionId]
                        if (session == null) {
                            logger.warn("[REDIS|HEALTH|$userId] Connection not found")
                            cancel("Connection terminated")
                            return@launch
                        }

                        if (inactiveDuration > (Config.getPongTimeout() * 2)) {  // Double the timeout
                            logger.warn("[REDIS|HEALTH|$userId] Inactive connection")
                            webSocketManager.closeAndCleanupConnection(connectionId, session)
                        } else {
                            logger.debug("[REDIS|HEALTH|$userId] Connection active")
                        }

                        delay(25.seconds)
                    } catch (e: Exception) {
                        // 4. Improved error logging
                        logger.error(
                            """
                        Health check failed: ${e.javaClass.simpleName} - 
                        ${e.message ?: "No message"}
                    """.trimIndent(), e
                        )
                        delay(exponentialBackoff(++errorCount))
                    }
                }
            }
        }
    }

    fun stopMonitoringSubscription(userId: String) {
        subscriptionStates[userId]?.healthCheckJob?.cancel()
        subscriptionStates.remove(userId)
        logger.debug("[REDIS|HEALTH|{}] Stopped health monitoring", userId)
    }

    private fun exponentialBackoff(attempt: Int): Duration {
        return (2.0.pow(attempt).toLong() * 1000).coerceAtMost(30000).milliseconds
    }


    private suspend fun recreateSubscription(userId: String, connectionId: String) {
        for (attempt in 1..Config.getMaxRetryAttempts()) {
            try {
                val topic = client.getTopic("user:$userId")
                topic.removeListener()
                createSubscription(userId) { message ->
                    CoroutineScope(dispatcherProvider.redisSubscriptions).launch {
                        webSocketManager.handleIncomingMessage(connectionId, userId, message)
                    }
                }
                logger.info("Recreated Redis subscription for user $userId")
                return
            } catch (e: Exception) {
                logger.error("Failed to recreate subscription (attempt $attempt): ${e.message}")
                if (attempt < Config.getMaxRetryAttempts()) {
                    delay(exponentialBackoff(attempt))
                }
            }
        }
        logger.error("[$userId] Max retry attempts reached for subscription recreation")
    }

    suspend fun getActiveSessionsForUser(userId: String): List<String> = withContext(dispatcherProvider.network) {
        try {
            val userSessionsKey = "user_sessions:$userId"
            return@withContext client.getSet<String>(userSessionsKey).readAll().toList()
        } catch (e: Exception) {
            logger.error("Failed to get active sessions for user $userId: ${e.message}", e)
            emptyList()
        }
    }

    fun startSessionVerification() {
        scope.launch {
            while (isActive) {
                try {
                    logger.debug("Starting periodic session verification")

                    // Get all user session keys
                    val sessionKeysPattern = "user_sessions:*"
                    val sessionKeys = client.keys.getKeysByPattern(sessionKeysPattern)

                    var zombieCount = 0

                    sessionKeys.forEach { key ->
                        val userId = key.substringAfter("user_sessions:")
                        val sessionIds = client.getSet<String>(key).readAll().toList()

                        sessionIds.forEach { sessionId ->
                            // Check if connection exists in WebSocketManager
                            if (!webSocketManager.activeConnections.containsKey(sessionId)) {
                                // This is a zombie session
                                zombieCount++
                                logger.warn("Found zombie session: $sessionId for user $userId")
                                client.getSet<String>(key).remove(sessionId)
                            }
                        }
                    }

                    logger.info("Session verification complete. Found and cleaned up $zombieCount zombie sessions")

                    delay(5.minutes)
                } catch (e: Exception) {
                    logger.error("Error during session verification: ${e.message}", e)
                    delay(1.minutes)
                }
            }
        }
    }

    fun createSubscription(userId: String, messageHandler: (String) -> Unit) {
        val topic = client.getTopic("user:$userId")
        topic.addListener(String::class.java) { _, message ->
            // Launch in redisSubscriptions dispatcher to handle messages
            CoroutineScope(dispatcherProvider.redisSubscriptions).launch {
                try {
                    logger.info("[REDIS|{}] Received message: {}", userId, message.take(200))
                    messageHandler(message)
                } catch (e: Exception) {
                    logger.error("Error handling Redis message: ${e.message}", e)
                }
            }
        }
        logger.debug("Created subscription for user $userId")
    }

    fun shutdown() {
        try {
            client.shutdown()
            logger.info("Redis connection closed")
        } catch (e: Exception) {
            logger.error("Error shutting down Redis: ${e.message}", e)
        }
    }
}

