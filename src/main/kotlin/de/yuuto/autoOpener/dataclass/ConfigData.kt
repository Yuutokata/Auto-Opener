package de.yuuto.autoOpener.dataclass

import kotlinx.serialization.Serializable


@Serializable
data class ConfigData(
    val host: String,
    val port: Int,
    val redis: Redis,
    val mongodb: MongoDB,
    val tokens: Tokens,
    val jwt: JWT,
    val subscriptionConnectionPoolSize: Int,
    val subscriptionConnectionMinimumIdleSize: Int,
    val connectionPoolSize: Int,
    val maxMissedPongs: Int,
    val healthCheckInterval: Int,
    val heartBeatTimeout: Int,
    val heartBeatInterval: Int,
    val maxRetryAttempts: Int,
    val pongTimeout: Long,
    val subscriptionsPerConnection: Int,
    val getInactivityThreshold: Int,
    val rateLimits: RateLimits
)

@Serializable
data class RateLimit(
    val limit: Int, val duration: Long
)

@Serializable
data class RateLimits(
    val token: RateLimit, val websocket: RateLimit
)

@Serializable
data class Redis(
    val host: String, val port: Int
)

@Serializable
data class MongoDB(
    val uri: String, val db: String
)

@Serializable
data class Tokens(
    val bot: List<String>
)

@Serializable
data class JWT(
    val issuer: String, val audience: String, val secret: String
)