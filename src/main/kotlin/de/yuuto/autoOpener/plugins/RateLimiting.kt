package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.auth.JwtService
import de.yuuto.autoOpener.config.RateLimitSetting
import de.yuuto.autoOpener.config.RateLimitSettings
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.RateLimitProviderConfig

val TOKEN_LIMIT = RateLimitName("token")
val WEBSOCKET_LIMIT = RateLimitName("websocket")
val SERVICE_LIMIT = RateLimitName("service")

/**
 * Limits are applied per client: by IP for the unauthenticated token endpoint, by the authenticated
 * subject (user id or service token digest) everywhere else. Without this a single shared bucket would
 * let one noisy client lock everyone out.
 */
fun Application.configureRateLimit(settings: RateLimitSettings, clientIpHeader: String?) {
    install(RateLimit) {
        register(TOKEN_LIMIT) { perClient(settings.token) { call -> call.clientIp(clientIpHeader) } }
        register(WEBSOCKET_LIMIT) { perClient(settings.websocket) { call -> call.subjectOrIp(clientIpHeader) } }
        register(SERVICE_LIMIT) { perClient(settings.service) { call -> call.subjectOrIp(clientIpHeader) } }
    }
}

private fun RateLimitProviderConfig.perClient(setting: RateLimitSetting, key: (ApplicationCall) -> String) {
    rateLimiter(limit = setting.limit, refillPeriod = setting.period)
    requestKey { call -> key(call) }
}

private fun ApplicationCall.subjectOrIp(header: String?): String =
    principal<JWTPrincipal>()?.payload?.getClaim(JwtService.TOKEN_CLAIM)?.asString() ?: clientIp(header)

/**
 * Behind a reverse proxy the peer address is the proxy. When [header] is set (for example `X-Forwarded-For`),
 * its last entry is used because that is the one appended by our own proxy and cannot be forged by the client.
 */
fun ApplicationCall.clientIp(header: String?): String {
    val forwarded = header?.let { request.headers[it] }?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    return forwarded ?: request.origin.remoteAddress
}
