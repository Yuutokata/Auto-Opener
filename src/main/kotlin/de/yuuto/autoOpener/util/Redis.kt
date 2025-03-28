package de.yuuto.autoOpener.util

import org.redisson.Redisson
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
import org.redisson.codec.JsonJacksonCodec
import java.util.concurrent.TimeUnit
import org.redisson.config.Config as RedissonConfig

class RedisManager() {
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
    private val dlq = client.getTopic("dead_letter_queue")

    init {
        sessionMap.clear()
    }

    fun storeSession(userId: String, connectionId: String) {
        sessionMap.put(connectionId, userId, 30, TimeUnit.MINUTES)
    }

    fun removeSession(userId: String, connectionId: String) {
        sessionMap.fastRemove(connectionId)
    }

    fun getTopic(userId: String): RTopic {
        return client.getTopic("user:$userId").apply {
            addListener(String::class.java) { _, msg ->
                if (!msg.matches(Regex("^https?://.*"))) {
                    dlq.publish("Invalid URL: $msg")
                }
            }
        }
    }

    fun publish(userId: String, message: String): Long {
        val topic = client.getTopic("user:$userId")
        return topic.publish(message)
    }

}
