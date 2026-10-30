package de.yuuto.autoOpener.websocket

import de.yuuto.autoOpener.auth.JwtService
import de.yuuto.autoOpener.auth.SERVICE_AUTH
import de.yuuto.autoOpener.auth.USER_AUTH
import de.yuuto.autoOpener.auth.isJwtStructure
import de.yuuto.autoOpener.config.WebSocketSettings
import de.yuuto.autoOpener.plugins.SERVICE_LIMIT
import de.yuuto.autoOpener.plugins.WEBSOCKET_LIMIT
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocketRaw
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close

fun Application.configureWebSockets(settings: WebSocketSettings) {
    install(WebSockets) {
        // Protocol-level pings stay off: KeepAlive sends its own and closes silent connections.
        maxFrameSize = settings.maxFrameSize
        masking = settings.masking
    }

    // Browsers send the token as WebSocket subprotocol and reject the handshake unless the server echoes it.
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path.startsWith("/listen/") || path == "/bot") {
            call.request.headers["Sec-WebSocket-Protocol"]?.takeIf(::isJwtStructure)?.let {
                call.response.headers.append("Sec-WebSocket-Protocol", it)
            }
        }
    }
}

fun Route.webSocketRoutes(manager: ConnectionManager) {
    authenticate(USER_AUTH) {
        rateLimit(WEBSOCKET_LIMIT) {
            webSocketRaw("/listen/{userId}") {
                val jwtUserId = call.principal<JWTPrincipal>()?.payload?.getClaim(JwtService.TOKEN_CLAIM)?.asString()
                val pathUserId = call.parameters["userId"]?.trim()
                when {
                    jwtUserId == null || pathUserId == null ->
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid credentials"))

                    jwtUserId != pathUserId ->
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "User ID mismatch"))

                    else -> manager.serveUser(this, jwtUserId)
                }
            }
        }
    }

    authenticate(SERVICE_AUTH) {
        rateLimit(SERVICE_LIMIT) {
            webSocketRaw("/bot") { manager.serveBot(this) }
        }
    }
}
