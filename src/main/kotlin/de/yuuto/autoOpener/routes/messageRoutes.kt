package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.RedisManager
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

fun Route.messageRoutes(dispatcherProvider: DispatcherProvider, redisManager: RedisManager) {
    val logger = LoggerFactory.getLogger(Route::class.java)
    rateLimit(RateLimitName("service")) {
        authenticate("auth-service") {
            get("/send_message") {
                val (receiverID, message, isValid) = withContext(dispatcherProvider.processing) {
                    val id = call.queryParameters["user_id"]
                    val msg = call.queryParameters["message"]

                    val valid = !(id.isNullOrBlank() || !id.matches(Regex("\\d+")) || msg.isNullOrBlank())
                    Triple(id, msg, valid)
                }

                if (receiverID.isNullOrBlank() || !receiverID.matches(Regex("\\d+"))) {
                    call.response.status(HttpStatusCode.BadRequest)
                    call.respondText("Invalid user ID.")
                    logger.warn("Invalid user ID provided: $receiverID")
                    return@get
                }

                if (message.isNullOrBlank()) {
                    call.response.status(HttpStatusCode.BadRequest)
                    call.respondText("Message cannot be empty.")
                    logger.warn("Empty message provided")
                    return@get
                }

                try {
                    withContext(dispatcherProvider.network) {
                        redisManager.publish(receiverID, message)
                    }

                    call.respondText("Message sent successfully.")
                    logger.info("Message sent to user $receiverID with message $message")
                } catch (e: Exception) {
                    call.response.status(HttpStatusCode.InternalServerError)
                    call.respondText("Failed to send message.")
                    logger.error("Error sending message to user $receiverID", e)
                }
            }
        }
    }
}
