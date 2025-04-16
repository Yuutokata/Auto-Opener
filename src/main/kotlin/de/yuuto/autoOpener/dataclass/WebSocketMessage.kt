package de.yuuto.autoOpener.dataclass

import kotlinx.serialization.Serializable

@Serializable
data class WebSocketMessage(
    val message: String, val url: String
)

@Serializable
data class WebsocketReceive(
    val userId: String,
    val message: WebSocketMessage
)

@Serializable
data class BotResponse(
    val status: String,
    val message: String,
    val userId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
