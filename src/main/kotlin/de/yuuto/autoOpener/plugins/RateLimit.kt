package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.util.Config
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.seconds

fun Application.configureRateLimit() {
    install(RateLimit) {
        register(RateLimitName("user")) {
            rateLimiter(limit = Config.getRateLimits().token.limit, refillPeriod = Config.getRateLimits().token.duration.seconds)
        }
        register(RateLimitName("websocket")) {
            rateLimiter(limit = Config.getRateLimits().websocket.limit, refillPeriod = Config.getRateLimits().websocket.duration.seconds)
        }
        register(RateLimitName("service")) {
            rateLimiter(limit = Config.getRateLimits().service.limit, refillPeriod = Config.getRateLimits().service.duration.seconds)
        }
    }

}