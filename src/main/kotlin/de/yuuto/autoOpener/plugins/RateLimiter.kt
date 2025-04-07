package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.util.Config
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun Application.configureRateLimiter() {
    install(RateLimit) {
        register(RateLimitName("service")) {
            rateLimiter(limit = 1000, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
        register(RateLimitName("token")) {
            rateLimiter(limit = Config.getRateLimits().token.limit, refillPeriod = Config.getRateLimits().token.duration.seconds)
            requestKey { call -> call.request.origin.remoteAddress }
        }
        register(RateLimitName("websocket")) {
            rateLimiter(limit = Config.getRateLimits().websocket.limit, refillPeriod = Config.getRateLimits().websocket.duration.seconds)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
}