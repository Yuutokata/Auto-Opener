package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.WebSocketManager
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

fun Route.messageRoutes(dispatcherProvider: DispatcherProvider, webSocketManager: WebSocketManager) {
    val logger = LoggerFactory.getLogger(Route::class.java)
    authenticate("auth-service") {
        get("/send_message") {
            val (receiverID, message, isValid) = withContext(dispatcherProvider.processing) {
                val id = call.queryParameters["user_id"]
                val msg = call.queryParameters["message"]

                val valid = !(id.isNullOrBlank() || !id.matches(Regex("\\d+")) || msg.isNullOrBlank())
                Triple(id, msg, valid)
            }

            if (!isValid) {
                call.response.status(HttpStatusCode.BadRequest)
                call.respondText("Invalid user ID or message.")
                logger.warn("Invalid input: user_id=$receiverID, message=$message")
                return@get
            }

            try {
                withContext(dispatcherProvider.network) {
                    val activeSessions = webSocketManager.getActiveSessionsForUser(receiverID!!)
                    if (activeSessions.isEmpty()) {
                        call.response.status(HttpStatusCode.NotFound)
                        call.respondText("No active sessions found for user.")
                        logger.warn("No active sessions for user $receiverID")
                        return@withContext
                    }

                    activeSessions.forEach { connectionId ->
                        webSocketManager.handleIncomingMessage(connectionId, receiverID, message!!)
                    }
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
