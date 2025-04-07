package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.util.Config
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureRateLimiter() {
    install(RateLimit) {
        register(RateLimitName("token")) {
            rateLimiter(limit = Config.getRateLimits().token.limit, refillPeriod = Config.getRateLimits().token.duration.seconds)
        }
        register(RateLimitName("websocket")) {
            rateLimiter(limit = Config.getRateLimits().websocket.limit, refillPeriod = Config.getRateLimits().websocket.duration.seconds)
        }
    }
}