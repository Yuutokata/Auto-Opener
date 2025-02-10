package de.yuuto.autoOpener.dataclass

import kotlinx.serialization.Serializable

@Serializable
data class WebSocket(
    val message: String,
    val url: String
)
