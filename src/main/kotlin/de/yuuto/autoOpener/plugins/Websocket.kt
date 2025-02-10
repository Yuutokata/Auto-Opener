package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.dataclass.WebSocket
import de.yuuto.autoOpener.util.Redis
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.redisson.api.RTopic
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

val userSessions = ConcurrentHashMap<String, WebSocketSession>()

fun Application.configureWebsockets() {
    val logger = LoggerFactory.getLogger(Application::class.java) // Logger instance
    val redissonClient = Redis.redissonClient // Assuming this function sets up your Redisson client
    install(WebSockets) {
        pingPeriod = 15.seconds
    }
    routing {
        authenticate("auth-jwt") { // Assuming you have JWT authentication configured
            webSocket("/listen") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asString()
                    ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No user ID"))
                logger.debug("Listening on websocket for user $userId")

                userSessions[userId] = this
                try {
                    val topic: RTopic = redissonClient.getTopic("user:$userId")
                    // Define listenerId outside the launch block
                    var listenerId: Int = -1

                    listenerId = topic.addListener(String::class.java) { channel, message ->
                        // Launch a coroutine to call the suspending function
                        launch {
                            logger.debug("Received message $message for user $userId")
                            try {
                                val jsonMessage = Json.encodeToString(WebSocket("Keyword Ping", message))
                                outgoing.send(Frame.Text(jsonMessage))

                            } catch (e: ClosedReceiveChannelException) {
                                topic.removeListener(listenerId) // Now accessible
                                userSessions.remove(userId)

                            } catch (e: Exception) {
                                logger.error("Error sending message: ${e.message}")
                            }
                        }
                    }

                    // Keep the connection alive until the client closes it
                    for (frame in incoming) {
                        val fool = null
                    }

                    // Remove the listener when the client closes the connection
                    topic.removeListener(listenerId)
                    userSessions.remove(userId)
                } catch (e: Exception) {
                    logger.error("Error in websocket for user $userId", e) // Log websocket errors with exception
                }
            }
        }
    }
}