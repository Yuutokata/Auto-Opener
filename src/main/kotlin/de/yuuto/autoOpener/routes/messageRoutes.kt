package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.dataclass.WebSocketMessage
import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.WebSocketManager
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.*

fun Route.messageRoutes(dispatcherProvider: DispatcherProvider, webSocketManager: WebSocketManager) {
    val logger = LoggerFactory.getLogger("MessageRoute")
    rateLimit(RateLimitName("service")) {
        authenticate("auth-service") {
            post("/send_message") {
                val callId = UUID.randomUUID().toString()
                MDC.put("call_id", callId)
                MDC.put("route", "/send_message")
                MDC.put("http_method", "POST")

                val receiverID = call.queryParameters["user_id"]
                MDC.put("target_user_id", receiverID ?: "null")
                logger.info("Received send message request")

                if (receiverID.isNullOrBlank() || !receiverID.matches(Regex("^\\d{15,20}$"))) {
                    MDC.put("validation_error", "Invalid or missing user_id parameter")
                    logger.warn("Send message failed: Invalid user ID parameter")
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or missing user_id parameter"))
                    MDC.clear()
                    return@post
                }

                try {
                    val messageData = call.receive<WebSocketMessage>()
                    MDC.put("message_type", messageData.message)
                    MDC.put("message_url_provided", messageData.url.isNotBlank().toString())

                    var sessionsFound = 0
                    var sessionsAttempted = 0
                    var sessionsFailed = 0

                    withContext(dispatcherProvider.network) {
                        val activeSessions = webSocketManager.getActiveSessionsForUser(receiverID)
                        sessionsFound = activeSessions.size
                        MDC.put("active_sessions_found", sessionsFound.toString())

                        if (activeSessions.isEmpty()) {
                            MDC.put("send_status", "failure")
                            MDC.put("reason", "No active sessions found")
                            logger.warn("No active sessions found for user, cannot send message")
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No active sessions found for user"))
                            return@withContext
                        }

                        sessionsAttempted = activeSessions.size
                        activeSessions.forEach { connectionId ->
                            launch {
                                try {
                                    webSocketManager.sendMessageToClient(connectionId, messageData.url, receiverID)
                                } catch (e: IllegalStateException) {
                                    sessionsFailed++
                                    MDC.put("event_type", "message_send_skipped")
                                    MDC.put("connection_id", connectionId)
                                    MDC.put("user_id", receiverID)
                                    MDC.put("reason", "Connection closed before send")
                                    logger.warn("Skipping send to closed connection")
                                    MDC.clear()
                                } catch (e: Exception) {
                                    sessionsFailed++
                                    MDC.put("event_type", "message_send_error_route")
                                    MDC.put("connection_id", connectionId)
                                    MDC.put("user_id", receiverID)
                                    logger.error("Unexpected error sending message via connection $connectionId", e)
                                    MDC.clear()
                                }
                            }
                        }
                    }

                    MDC.put("sessions_found", sessionsFound.toString())
                    MDC.put("sessions_attempted", sessionsAttempted.toString())
                    MDC.put("sessions_failed", sessionsFailed.toString())
                    val successfulSends = sessionsAttempted - sessionsFailed
                    MDC.put("successful_sends", successfulSends.toString())

                    if (successfulSends > 0) {
                        logger.info("Message sending process completed for user")
                        call.respond(
                            HttpStatusCode.OK, mapOf("message" to "Message sent to $successfulSends active session(s).")
                        )
                    } else if (sessionsFound > 0) {
                        logger.error("Message sending failed for all $sessionsFound found sessions")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to send message to any active session.")
                        )
                    } else {
                        if (call.response.status() == null) {
                            logger.warn("Responding NotFound post-context (should have happened earlier)")
                            call.respond(
                                HttpStatusCode.NotFound, mapOf("error" to "No active sessions found for user.")
                            )
                        }
                    }

                } catch (e: ContentTransformationException) {
                    MDC.put("error_type", "ContentTransformationException")
                    logger.error("Invalid send message request format", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request body. Expected JSON with 'message' and 'url'.")
                    )
                } catch (e: Exception) {
                    MDC.put("error_type", e::class.simpleName ?: "UnknownException")
                    logger.error("Error processing send message request", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Unknown error processing message"))
                    )
                } finally {
                    MDC.clear()
                }
            }
        }
    }
}
