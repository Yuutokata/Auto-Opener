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