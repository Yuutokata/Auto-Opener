package de.yuuto.autoOpener.dataclass

import kotlinx.serialization.Serializable


@Serializable
data class ConfigData(
    val host: String,
    val port: Int,
    val mongodb: MongoDB,
    val tokens: Tokens,
    val jwt: JWT,
    val healthCheckInterval: Int,
    val pongTimeout: Long,
    val getInactivityThreshold: Int,
    val rateLimits: RateLimits,
    val websocket: WebSocketConfig = WebSocketConfig(),
    val dispatchers: DispatcherConfig = DispatcherConfig()
)

@Serializable
data class WebSocketConfig(
    val timeout: Int = 90, val maxFrameSize: Long = 65536, val masking: Boolean = false
)

@Serializable
data class DispatcherConfig(
    val networkParallelism: Int = 30,
    val databaseParallelism: Int = 10,
    val processingParallelism: Int = 8,
    val monitoringParallelism: Int = 4,
    val websocketParallelism: Int = 6,
    val heartbeatParallelism: Int = 2,
    val monitoringIntervalSeconds: Int = 120
)

@Serializable
data class RateLimit(
    val limit: Int, val duration: Long
)

@Serializable
data class RateLimits(
    val token: RateLimit, val websocket: RateLimit, val service: RateLimit
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
