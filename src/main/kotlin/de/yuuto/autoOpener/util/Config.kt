package de.yuuto.autoOpener.util

import de.yuuto.autoOpener.dataclass.ConfigData
import de.yuuto.autoOpener.dataclass.RateLimits
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

    fun getHost(): String = configData.host
    fun getPort(): Int = configData.port
    fun getRedisHost(): String = configData.redis.host
    fun getRedisPort(): Int = configData.redis.port
    fun getMongoDBUri(): String = configData.mongodb.uri
    fun getMongoDBDatabase(): String = configData.mongodb.db
    fun getHealthCheckInterval(): Int = configData.healthCheckInterval
    fun getMaxRetryAttempts(): Int = configData.maxRetryAttempts
    fun getPongTimeout(): Long = configData.pongTimeout
    fun getInactivityThreshold(): Int = configData.getInactivityThreshold
    fun getSubscriptionsPerConnection(): Int = configData.subscriptionsPerConnection
    fun getRateLimits(): RateLimits = configData.rateLimits
    fun getBotToken(): List<String> = configData.tokens.bot
    fun getIssuer(): String = configData.jwt.issuer
    fun getAudience(): String = configData.jwt.audience
    fun getSecret(): String = configData.jwt.secret
    fun getSubscriptionConnectionPoolSize(): Int = configData.subscriptionConnectionPoolSize
    fun getSubscriptionConnectionMinimumIdleSize(): Int = configData.subscriptionConnectionMinimumIdleSize
    fun getConnectionPoolSize(): Int = configData.connectionPoolSize
}