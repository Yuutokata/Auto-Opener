package de.yuuto.autoOpener.websocket

import kotlinx.serialization.Serializable

/** Payload delivered to a user's extension. */
@Serializable
data class WebSocketMessage(val message: String, val url: String)

/** Frame sent by the bot: forward [message] to every live connection of [userId]. */
@Serializable
data class WebsocketReceive(val userId: String, val message: WebSocketMessage)

/** Reply to the bot for each forwarded message. */
@Serializable
data class BotResponse(
    val status: String,
    val message: String,
    val userId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
