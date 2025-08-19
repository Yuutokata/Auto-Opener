package de.yuuto.autoOpener.util

import de.yuuto.autoOpener.dataclass.*
import io.github.cdimascio.dotenv.dotenv

object Config {
    private val dotenv = dotenv { ignoreIfMissing = true }

    fun getHost(): String = getenvOrDotenv("HOST", "0.0.0.0")
    fun getPort(): Int = getenvOrDotenv("SERVER_PORT", "8080").toInt()

    fun getMongoDBUri(): String = getenvOrDotenv("MONGO_URI", "mongodb://localhost:27017")
    fun getMongoDBDatabase(): String = getenvOrDotenv("MONGO_DB", "autoopener")

    fun getHealthCheckInterval(): Int = getenvOrDotenv("HEALTH_CHECK_INTERVAL", "60").toInt()
    fun getPongTimeout(): Long = getenvOrDotenv("PONG_TIMEOUT", "30000").toLong()
    fun getInactivityThreshold(): Int = getenvOrDotenv("INACTIVITY_THRESHOLD", "300").toInt()

    fun getRateLimits(): RateLimits = RateLimits(
        token = RateLimit(
            limit = getenvOrDotenv("RATE_LIMIT_TOKEN", "10").toInt(),
            duration = getenvOrDotenv("RATE_LIMIT_TOKEN_DURATION", "60").toLong()
        ),
        websocket = RateLimit(
            limit = getenvOrDotenv("RATE_LIMIT_WS", "20").toInt(),
            duration = getenvOrDotenv("RATE_LIMIT_WS_DURATION", "60").toLong()
        ),
        service = RateLimit(
            limit = getenvOrDotenv("RATE_LIMIT_SERVICE", "5").toInt(),
            duration = getenvOrDotenv("RATE_LIMIT_SERVICE_DURATION", "60").toLong()
        )
    )

    fun getBotToken(): List<String> = getenvOrDotenv("BOT_TOKEN", "testtoken").split(",")
    fun getIssuer(): String = getenvOrDotenv("JWT_ISSUER", "autoopener")
    fun getAudience(): String = getenvOrDotenv("JWT_AUDIENCE", "autoopener-users")
    fun getSecret(): String = getenvOrDotenv("JWT_SECRET", "secret")

    fun getWebSocketTimeout(): Int = getenvOrDotenv("WS_TIMEOUT", "90").toInt()
    fun getWebSocketMaxFrameSize(): Long = getenvOrDotenv("WS_MAX_FRAME_SIZE", "65536").toLong()
    fun getWebSocketMasking(): Boolean = getenvOrDotenv("WS_MASKING", "false").toBoolean()

    fun getNetworkParallelism(): Int = getenvOrDotenv("NETWORK_PARALLELISM", "30").toInt()
    fun getDatabaseParallelism(): Int = getenvOrDotenv("DATABASE_PARALLELISM", "10").toInt()
    fun getProcessingParallelism(): Int = getenvOrDotenv("PROCESSING_PARALLELISM", "8").toInt()
    fun getMonitoringParallelism(): Int = getenvOrDotenv("MONITORING_PARALLELISM", "4").toInt()
    fun getWebsocketParallelism(): Int = getenvOrDotenv("WEBSOCKET_PARALLELISM", "6").toInt()
    fun getHeartbeatParallelism(): Int = getenvOrDotenv("HEARTBEAT_PARALLELISM", "2").toInt()
    fun getMonitoringIntervalSeconds(): Int = getenvOrDotenv("MONITORING_INTERVAL_SECONDS", "120").toInt()

    private fun getenvOrDotenv(key: String, default: String): String {
        return System.getenv(key) ?: dotenv[key] ?: default
    }
}
