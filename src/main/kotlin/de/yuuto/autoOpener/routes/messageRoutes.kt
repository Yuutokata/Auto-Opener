package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.redisManager
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

fun Route.messageRoutes() {
    val logger = LoggerFactory.getLogger(Route::class.java)
    authenticate("auth-service") {
        get("/send_message") {
            val receiverID = call.queryParameters["user_id"]
            val message = call.queryParameters["message"]

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
                redisManager.publish(receiverID, message)

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
