package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.auth.SERVICE_AUTH
import de.yuuto.autoOpener.plugins.ErrorResponse
import de.yuuto.autoOpener.plugins.SERVICE_LIMIT
import de.yuuto.autoOpener.users.userIdPattern
import de.yuuto.autoOpener.websocket.UserNotifier
import de.yuuto.autoOpener.websocket.WebSocketMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.messageRoutes(notifier: UserNotifier) {
    authenticate(SERVICE_AUTH) {
        rateLimit(SERVICE_LIMIT) {
            post("/send_message") {
                val receiverId = call.request.queryParameters["user_id"]
                if (receiverId.isNullOrBlank() || !userIdPattern.matches(receiverId)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid or missing user_id parameter"))
                    return@post
                }

                // The extension receives the bare URL on this path, unlike the JSON object sent through the bot socket.
                val message = call.receive<WebSocketMessage>()
                val delivery = notifier.send(receiverId, message.url)
                when {
                    delivery.found == 0 ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("No active sessions found for user"))

                    delivery.delivered == 0 ->
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to send message to any active session."))

                    else -> call.respond(MessageResponse("Message sent to ${delivery.delivered} active session(s)."))
                }
            }
        }
    }
}
