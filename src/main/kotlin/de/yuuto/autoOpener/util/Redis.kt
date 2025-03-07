package de.yuuto.autoOpener.util

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import de.yuuto.autoOpener.util.*
import org.redisson.codec.JsonJacksonCodec
import org.redisson.config.Config
import de.yuuto.autoOpener.util.Config as ProjectConfig


object Redis {
    private val redisConfig = Config().apply {
        useSingleServer().apply {
            address = "redis://${ProjectConfig.getRedisHost()}:${ProjectConfig.getRedisPort()}"
            // Wichtig: Connection-Pool für Subscriptions erhöhen
            subscriptionConnectionPoolSize = ProjectConfig.getSubscriptionConnectionPoolSize()  // Standard: 50
            connectionPoolSize = ProjectConfig.getConnectionPoolSize()           // Standard: 64 (kann bleiben)
            subscriptionConnectionMinimumIdleSize = ProjectConfig.getSubscriptionConnectionMinimumIdleSize() // Verbindungen im Leerlauf
        }
        codec = JsonJacksonCodec()
    }

    val redissonClient: RedissonClient by lazy { Redisson.create(redisConfig) }
}
