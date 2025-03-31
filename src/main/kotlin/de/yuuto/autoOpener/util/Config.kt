package de.yuuto.autoOpener.util

import de.yuuto.autoOpener.dataclass.ConfigData
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
    fun getMaxMissedPongs(): Int = configData.maxMissedPongs
    fun getHealthCheckInterval(): Int = configData.healthCheckInterval
    fun getMaxRetryAttempts(): Int = configData.maxRetryAttempts
    fun getHeartBeatTimeout(): Int = configData.heartBeatTimeout
    fun getHeartBeatInterval(): Int = configData.heartBeatInterval
    fun getBotToken(): List<String> = configData.tokens.bot
    fun getIssuer(): String = configData.jwt.issuer
    fun getAudience(): String = configData.jwt.audience
    fun getSecret(): String = configData.jwt.secret
    fun getSubscriptionConnectionPoolSize(): Int = configData.subscriptionConnectionPoolSize
    fun getSubscriptionConnectionMinimumIdleSize(): Int = configData.subscriptionConnectionMinimumIdleSize
    fun getConnectionPoolSize(): Int = configData.connectionPoolSize
}