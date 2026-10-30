package de.yuuto.autoOpener.config

import io.github.cdimascio.dotenv.dotenv
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

data class RateLimitSetting(val limit: Int, val period: Duration)

data class RateLimitSettings(
    val token: RateLimitSetting,
    val websocket: RateLimitSetting,
    val service: RateLimitSetting,
)

data class JwtSettings(
    val secret: String,
    val issuer: String,
    val audience: String,
    val ttl: Duration,
)

data class WebSocketSettings(
    val maxFrameSize: Long,
    val masking: Boolean,
    val healthCheckInterval: Duration,
    val pingInterval: Duration,
    val pongTimeout: Duration,
    val maxConnectionsPerUser: Int,
)

data class AppConfig(
    val host: String,
    val port: Int,
    val mongoUri: String,
    val mongoDatabase: String,
    val serviceTokens: Set<String>,
    val jwt: JwtSettings,
    val rateLimits: RateLimitSettings,
    val webSocket: WebSocketSettings,
    val requireUserSecret: Boolean,
    val clientIpHeader: String?,
) {
    companion object {
        private val serviceTokenPattern = Regex("^[a-zA-Z0-9]{32,128}$")

        /** Reads real environment variables first, then an optional local `.env` file. */
        fun fromEnvironment(): AppConfig {
            val dotenv = dotenv { ignoreIfMissing = true }
            return from { key -> System.getenv(key) ?: dotenv[key] }
        }

        /** Builds the configuration from [lookup]; throws [ConfigurationException] on missing or invalid values. */
        fun from(lookup: (String) -> String?): AppConfig {
            val env = EnvReader(lookup)
            val serviceTokens = env.required("BOT_TOKEN").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (serviceTokens.isEmpty() || serviceTokens.any { !serviceTokenPattern.matches(it) }) {
                throw ConfigurationException("BOT_TOKEN must contain comma-separated tokens of 32-128 alphanumeric characters")
            }

            return AppConfig(
                host = env.string("HOST", "0.0.0.0"),
                port = env.int("SERVER_PORT", 8080),
                mongoUri = env.required("MONGO_URI"),
                mongoDatabase = env.string("MONGO_DB", "AutoOpener"),
                serviceTokens = serviceTokens,
                jwt = JwtSettings(
                    secret = env.required("JWT_SECRET"),
                    issuer = env.string("JWT_ISSUER", "auto-opener"),
                    audience = env.string("JWT_AUDIENCE", "chrome-extension"),
                    ttl = env.int("JWT_TTL_HOURS", 72).hours,
                ),
                rateLimits = RateLimitSettings(
                    token = env.rateLimit("RATE_LIMIT_TOKEN", 20),
                    websocket = env.rateLimit("RATE_LIMIT_WS", 40),
                    service = env.rateLimit("RATE_LIMIT_SERVICE", 100),
                ),
                webSocket = WebSocketSettings(
                    maxFrameSize = env.long("WS_MAX_FRAME_SIZE", 65536),
                    masking = env.boolean("WS_MASKING", false),
                    healthCheckInterval = env.int("HEALTH_CHECK_INTERVAL", 5).seconds,
                    pingInterval = env.int("PING_INTERVAL", 25).seconds,
                    pongTimeout = env.int("PONG_TIMEOUT", 25).seconds,
                    maxConnectionsPerUser = env.int("MAX_CONNECTIONS_PER_USER", 3),
                ),
                requireUserSecret = env.boolean("REQUIRE_USER_SECRET", false),
                clientIpHeader = env.optional("CLIENT_IP_HEADER"),
            )
        }
    }
}

class ConfigurationException(message: String) : IllegalStateException(message)

private class EnvReader(private val lookup: (String) -> String?) {
    fun optional(key: String): String? = lookup(key)?.trim()?.takeIf { it.isNotEmpty() }

    fun required(key: String): String =
        optional(key) ?: throw ConfigurationException("Missing required environment variable $key")

    fun string(key: String, default: String): String = optional(key) ?: default

    fun int(key: String, default: Int): Int = parse(key, default) { it.toInt() }

    fun long(key: String, default: Long): Long = parse(key, default) { it.toLong() }

    fun boolean(key: String, default: Boolean): Boolean = parse(key, default) {
        when (it.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw NumberFormatException(it)
        }
    }

    fun rateLimit(prefix: String, defaultLimit: Int): RateLimitSetting = RateLimitSetting(
        limit = int(prefix, defaultLimit),
        period = int("${prefix}_DURATION", 60).seconds,
    )

    private fun <T> parse(key: String, default: T, transform: (String) -> T): T {
        val raw = optional(key) ?: return default
        return try {
            transform(raw)
        } catch (_: NumberFormatException) {
            throw ConfigurationException("Environment variable $key has an invalid value")
        }
    }
}
