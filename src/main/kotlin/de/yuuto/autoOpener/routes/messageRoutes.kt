package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.util.Config
import de.yuuto.autoOpener.util.Redis
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.redisson.api.RList
import org.redisson.api.RTopic
import org.slf4j.LoggerFactory
import java.util.*

fun Route.messageRoutes() {
    val redissonClient = Redis.redissonClient
    val logger = LoggerFactory.getLogger(Route::class.java) // Get logger instance

    get("/send_message") {
        val receiverID = call.queryParameters["user_id"]
        val message = call.queryParameters["message"]
        val token = call.request.headers["Authorization"]

        if (token == null || token!= Config.getBotToken()) {
            call.response.status(HttpStatusCode.Unauthorized)
            call.respondText("Unauthorized.")
            logger.warn("Unauthorized access attempt to /send_message") // Log unauthorized access
            return@get // Stop processing the request
        }

        // Input validation
        if (receiverID.isNullOrBlank() ||!receiverID.matches(Regex("\\d+"))) {
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
            val topic: RTopic = redissonClient.getTopic("user:$receiverID")

            topic.publish(message)

            call.respondText("Message sent successfully.")
            logger.info("Message sent to user $receiverID with message $message") // Log successful message sending
        } catch (e: Exception) {
            call.response.status(HttpStatusCode.InternalServerError)
            call.respondText("Failed to send message.")
            logger.error("Error sending message to user $receiverID", e) // Log error with exception
        }
    }
}