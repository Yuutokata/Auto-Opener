package de.yuuto.autoOpener.util

import kotlinx.coroutines.*
import org.redisson.Redisson
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
import org.redisson.codec.JsonJacksonCodec
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.redisson.config.Config as RedissonConfig

class RedisManager(private val dispatcherProvider: DispatcherProvider, private val webSocketManager: WebSocketManager) {
    private val redisConfig = RedissonConfig().apply {
        useSingleServer().apply {
            address = "redis://${Config.getRedisHost()}:${Config.getRedisPort()}"
            subscriptionConnectionPoolSize = Config.getSubscriptionConnectionPoolSize()
            connectionPoolSize = Config.getConnectionPoolSize()
            connectionMinimumIdleSize = Config.getSubscriptionConnectionMinimumIdleSize()
        }
        codec = JsonJacksonCodec()
    }
    private val client: RedissonClient = Redisson.create(redisConfig)
    private val sessionMap = client.getMapCache<String, String>("active_sessions")
    private val logger = LoggerFactory.getLogger(RedisManager::class.java)
    private val dlq = client.getTopic("dead_letter_queue")

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
        CoroutineScope(dispatcherProvider.monitoring).launch {
            while (true) {
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
        return client.getTopic("user:$userId").apply {
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
                        // Existing logic
                        logger.info("[REDIS|{}|{}] Processing completed", userId, traceId)
                    }
                } catch (e: Exception) {
                    logger.error(
                        "[REDIS|{}|{}] Processing failed: {}", userId, traceId, e.message
                    )
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
            healthCheckJob = CoroutineScope(dispatcherProvider.monitoring).launch {
                var consecutiveFailures = 0
                while (isActive) {
                    try {
                        val checkStart = System.currentTimeMillis()
                        val testChannel = "health_check:$userId"
                        val testTopic = client.getTopic(testChannel)

                        val responseReceived = CompletableDeferred<Boolean>()
                        val listenerId = testTopic.addListener(String::class.java) { _, _ ->
                            responseReceived.complete(true)
                        }

                        testTopic.publish("PING")

                        // Wait for response with timeout
                        withTimeoutOrNull(5.seconds) {
                            responseReceived.await()
                        }?.let {
                            val latency = System.currentTimeMillis() - checkStart
                            logger.debug("[REDIS|HEALTH|{}] Health check passed ({}ms)", userId, latency)
                            consecutiveFailures = 0
                            lastSuccessfulCheck = System.currentTimeMillis()
                        } ?: run {
                            logger.warn("[REDIS|HEALTH|{}] Health check timeout", userId)
                            consecutiveFailures++
                        }

                        testTopic.removeListener(listenerId)

                        if (consecutiveFailures >= 3) {
                            logger.error("[REDIS|HEALTH|{}] Critical failure - recreating subscription", userId)
                            recreateSubscription(userId, connectionId)
                            consecutiveFailures = 0
                            delay(1.minutes) // Backoff after recreation
                        } else {
                            delay(30.seconds)
                        }

                    } catch (e: Exception) {
                        logger.error("[REDIS|HEALTH|{}] Check failed: {}", userId, e.message)
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
