package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.dataclass.WebSocketMessage
import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.WebSocketManager
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

fun Route.messageRoutes(dispatcherProvider: DispatcherProvider, webSocketManager: WebSocketManager) {
    val logger = LoggerFactory.getLogger(Route::class.java)
    authenticate("auth-service") {
        post("/send_message") {
            val receiverID = call.queryParameters["user_id"]
            
            if (receiverID.isNullOrBlank() || !receiverID.matches(Regex("^\\d{15,20}$"))) {
                call.response.status(HttpStatusCode.BadRequest)
                call.respondText("Invalid user ID.")
                logger.warn("Invalid user ID: $receiverID")
                return@post
            }
            
            try {
                val messageData = call.receive<WebSocketMessage>()
                
                withContext(dispatcherProvider.network) {
                    val activeSessions = webSocketManager.getActiveSessionsForUser(receiverID)
                    if (activeSessions.isEmpty()) {
                        call.response.status(HttpStatusCode.NotFound)
                        call.respondText("No active sessions found for user.")
                        logger.warn("No active sessions for user $receiverID")
                        return@withContext
                    }

                    activeSessions.forEach { connectionId ->
                        try {
                            webSocketManager.handleIncomingMessage(connectionId, receiverID, messageData.url)
                        } catch (e: IllegalStateException) {
                            logger.warn("Skipping closed connection: $connectionId")
                            webSocketManager.cleanupConnection(connectionId)
                        }
                    }
                }

                call.respondText("Message sent successfully.")
                logger.info("Message sent to user $receiverID with message ${messageData.message}")
            } catch (e: Exception) {
                call.response.status(HttpStatusCode.InternalServerError)
                call.respondText("Failed to send message: ${e.message}")
                logger.error("Error sending message to user $receiverID", e)
            }
        }
    }
}
