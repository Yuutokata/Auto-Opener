package de.yuuto.autoOpener.dataclass

import kotlinx.serialization.Serializable


@Serializable
data class ConfigData(
    val host: String,
    val port: Int,
    val redis: Redis,
    val tokens: Tokens

)

@Serializable
data class Redis(
    val host: String,
    val port: Int
)

@Serializable
data class Tokens(
    val bot: String,
    val user: String
)