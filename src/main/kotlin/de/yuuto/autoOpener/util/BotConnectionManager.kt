package de.yuuto.autoOpener.util

import de.yuuto.autoOpener.dataclass.WebSocketMessage
import de.yuuto.autoOpener.dataclass.WebsocketReceive
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class BotConnectionManager(
    private val dispatcherProvider: DispatcherProvider, private val webSocketManager: WebSocketManager
) {
    private val logger = LoggerFactory.getLogger(BotConnectionManager::class.java)
    private val activeBotConnections = ConcurrentHashMap<String, WebSocketSession>()
    private val botConnectionIds = ConcurrentHashMap<String, String>()
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcherProvider.websocket + supervisorJob)

    fun registerBotConnection(botId: String, session: WebSocketSession, connectionId: String) {
        activeBotConnections[botId] = session
        botConnectionIds[botId] = connectionId

        webSocketManager.registerActiveConnection(
            connectionId = connectionId,
            session = session,
            botId = botId
        )

        scope.launch {
            try {
                delay(1.seconds)
                logger.info("[BOT:$connectionId] Connection established")
            } catch (e: Exception) {
                logger.error("Monitoring error for bot $botId: ${e.message}")
            }
        }
    }

    fun handleBotMessage(frame: Frame.Text, botId: String, connectionId: String) {
        scope.launch {
            try {
                val message = frame.readText()
                logger.debug("[BOT:$botId] Received message: ${message.take(200)}")

                val parsedMessage = Json.decodeFromString<WebsocketReceive>(message)
                forwardToUserSessions(botId, parsedMessage.message, parsedMessage.userId, connectionId = connectionId)
            } catch (e: Exception) {
                logger.error("[BOT:$botId] Message handling error: ${e.message}")
            }
        }
    }

    private suspend fun forwardToUserSessions(botId: String, message: WebSocketMessage, targetId: String, connectionId: String) {
        webSocketManager.getActiveUserSessions().forEach { (userId, sessionIds) ->
            if (userId == targetId) {
                val data = Json.encodeToString(WebSocketMessage.serializer(), message)
                sessionIds.forEach { sessionId ->
                    webSocketManager.sendToSession(sessionId, data)
                    logger.debug("[BOT:$connectionId] Forwarded message to user $userId")
                }
            }
        }
    }

    suspend fun cleanupBotConnection(botId: String, connectionId: String) {
        activeBotConnections.remove(botId)
        botConnectionIds.remove(botId)

        webSocketManager.cleanupConnection(connectionId)
        logger.info("[BOT:$botId] Connection resources cleaned up")
    }

    suspend fun shutdown() {
        activeBotConnections.values.forEach { it.close() }
        supervisorJob.cancel()
    }
}
