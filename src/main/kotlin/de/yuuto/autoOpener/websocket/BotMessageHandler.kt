package de.yuuto.autoOpener.websocket

import de.yuuto.autoOpener.users.userIdPattern
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Handles text frames from the bot and forwards them to the target user. */
class BotMessageHandler(private val notifier: UserNotifier) {
    private val logger = LoggerFactory.getLogger(BotMessageHandler::class.java)
    private val decoder = Json { ignoreUnknownKeys = true }
    private val encoder = Json { encodeDefaults = true; explicitNulls = false }

    suspend fun handle(bot: Connection, text: String) {
        val request = try {
            decoder.decodeFromString<WebsocketReceive>(text)
        } catch (e: SerializationException) {
            logger.warn("Invalid frame from {}: {}", bot, e.message)
            reply(bot, "error", "Invalid message format")
            return
        }

        if (!userIdPattern.matches(request.userId)) {
            logger.warn("Invalid user id in frame from {}", bot)
            reply(bot, "error", "Invalid user ID format", request.userId)
            return
        }

        val delivery = notifier.send(request.userId, Json.encodeToString(request.message))
        when {
            delivery.found == 0 -> {
                logger.info("No live connection for user {}", request.userId)
                reply(bot, "warn", "No active sessions found for user", request.userId)
            }

            delivery.delivered == 0 ->
                reply(bot, "error", "Failed to deliver message to any active sessions", request.userId)

            else -> {
                logger.info("Forwarded message for user {} to {}/{} connections", request.userId, delivery.delivered, delivery.found)
                reply(bot, "success", "Message delivered to ${delivery.delivered}/${delivery.found} active sessions", request.userId)
            }
        }
    }

    private suspend fun reply(bot: Connection, status: String, message: String, userId: String? = null) {
        try {
            bot.sendText(encoder.encodeToString(BotResponse(status, message, userId)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not reply to {}: {}", bot, e.message)
        }
    }
}
