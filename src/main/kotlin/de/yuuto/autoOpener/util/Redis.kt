package de.yuuto.autoOpener.util

import org.redisson.Redisson
import org.redisson.api.RedissonClient

import org.redisson.codec.JsonJacksonCodec
import org.redisson.config.Config

object Redis {
    private val redisConfig = Config().apply {
        useSingleServer().apply {
            address = "redis://${Config.getRedisHost()}:${Config.getRedisPort()}"
            // Wichtig: Connection-Pool für Subscriptions erhöhen
            subscriptionConnectionPoolSize = Config.getSubscriptionConnectionPoolSize()  // Standard: 50
            connectionPoolSize = 64               // Standard: 64 (kann bleiben)
            subscriptionConnectionMinimumIdleSize = 20 // Verbindungen im Leerlauf
        }
        codec = JsonJacksonCodec()
    }

    val redissonClient: RedissonClient by lazy { Redisson.create(redisConfig) }
}
