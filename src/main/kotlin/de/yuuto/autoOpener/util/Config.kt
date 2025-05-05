package de.yuuto.autoOpener.util

import de.yuuto.autoOpener.dataclass.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

object Config {
    private val configFile: Path by lazy { Path("./config.json") }
    private val configData: ConfigData by lazy {
        if (!configFile.exists()) {
            throw IllegalStateException("Config file not found at ${configFile.toAbsolutePath()}")
        }

        try {
            Json.decodeFromString(configFile.readText())
        } catch (e: SerializationException) {
            throw IllegalStateException("Failed to parse config file", e)
        }
    }

    // Basic configuration
    fun getHost(): String = configData.host
    fun getPort(): Int = configData.port

    // MongoDB configuration
    fun getMongoDBUri(): String = configData.mongodb.uri
    fun getMongoDBDatabase(): String = configData.mongodb.db

    // Connection configuration
    fun getHealthCheckInterval(): Int = configData.healthCheckInterval
    fun getPongTimeout(): Long = configData.pongTimeout
    fun getInactivityThreshold(): Int = configData.getInactivityThreshold

    // Rate limits
    fun getRateLimits(): RateLimits = configData.rateLimits

    // Authentication
    fun getBotToken(): List<String> = configData.tokens.bot
    fun getIssuer(): String = configData.jwt.issuer
    fun getAudience(): String = configData.jwt.audience
    fun getSecret(): String = configData.jwt.secret

    // WebSocket configuration
    fun getWebSocketTimeout(): Int = configData.websocket.timeout
    fun getWebSocketMaxFrameSize(): Long = configData.websocket.maxFrameSize
    fun getWebSocketMasking(): Boolean = configData.websocket.masking

    // Dispatcher configuration
    fun getNetworkParallelism(): Int = configData.dispatchers.networkParallelism
    fun getDatabaseParallelism(): Int = configData.dispatchers.databaseParallelism
    fun getProcessingParallelism(): Int = configData.dispatchers.processingParallelism
    fun getMonitoringParallelism(): Int = configData.dispatchers.monitoringParallelism
    fun getWebsocketParallelism(): Int = configData.dispatchers.websocketParallelism
    fun getHeartbeatParallelism(): Int = configData.dispatchers.heartbeatParallelism
    fun getMonitoringIntervalSeconds(): Int = configData.dispatchers.monitoringIntervalSeconds
}
