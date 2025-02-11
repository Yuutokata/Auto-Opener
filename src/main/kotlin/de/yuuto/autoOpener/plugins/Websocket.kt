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
    val logger = LoggerFactory.getLogger(Application::class.java)
    val redissonClient = Redis.redissonClient

    install(WebSockets) {
        pingPeriod = 15.seconds
    }

    routing {
        authenticate("auth-jwt") {
            webSocket("/listen/{userId}") {
                // Extract user ID from JWT
                val principal = call.principal<JWTPrincipal>()
                val jwtUserId = principal?.payload?.getClaim("userId")?.asString()
                    ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid token"))

                // Extract user ID from path parameter
                val pathUserId = call.parameters["userId"]
                    ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing user ID"))

                // Validate path parameter matches JWT claim
                if (jwtUserId != pathUserId) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "User ID mismatch"))
                    return@webSocket
                }

                logger.debug("WebSocket connected for user $jwtUserId")
                userSessions[jwtUserId] = this

                try {
                    val topic: RTopic = redissonClient.getTopic("user:$jwtUserId")
                    var listenerId: Int = -1

                    listenerId = topic.addListener(String::class.java) { _, message ->
                        launch {
                            try {
                                val jsonMessage = Json.encodeToString(WebSocket("Keyword Ping", message))
                                outgoing.send(Frame.Text(jsonMessage))
                            } catch (e: ClosedReceiveChannelException) {
                                topic.removeListener(listenerId)
                                userSessions.remove(jwtUserId)
                            } catch (e: Exception) {
                                logger.error("Error sending message: ${e.message}")
                            }
                        }
                    }

                    // Keep connection alive
                    for (frame in incoming) {
                        val fool = null
                    }

                    // Cleanup
                    topic.removeListener(listenerId)
                    userSessions.remove(jwtUserId)
                } catch (e: Exception) {
                    when (e) {
                        is java.io.IOException -> {
                            logger.debug("WebSocket disconnected for user $jwtUserId")
                            userSessions.remove(jwtUserId)
                        }
                        else -> {
                            logger.error("Unexpected error for user $jwtUserId: ${e.message}")
                            userSessions.remove(jwtUserId)
                        }
                    }
                }
            }
        }
    }
}