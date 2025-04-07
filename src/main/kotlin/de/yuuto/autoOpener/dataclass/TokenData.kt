package de.yuuto.autoOpener.dataclass

import kotlinx.serialization.Serializable

@Serializable
data class TokenRequest(
    val token: String, val role: String? = "user"
)
