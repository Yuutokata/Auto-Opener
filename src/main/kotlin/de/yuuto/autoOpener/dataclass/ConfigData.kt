package de.yuuto.autoOpener.dataclass

import kotlinx.serialization.Serializable


@Serializable
data class ConfigData(
    val host: String,
    val port: Int,
    val redis: Redis,
    val tokens: Tokens,
    val jwt: JWT,
    val subscriptionConnectionPoolSize: Int,
    val subscriptionConnectionMinimumIdleSize: Int,
    val connectionPoolSize: Int
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

@Serializable
data class JWT(
    val issuer: String,
    val audience: String,
    val secret: String
)