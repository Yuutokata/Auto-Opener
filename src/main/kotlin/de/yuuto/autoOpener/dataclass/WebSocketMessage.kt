package de.yuuto.autoOpener.dataclass

import kotlinx.serialization.Serializable

@Serializable
data class WebSocketMessage(
    val message: String, val url: String
)
