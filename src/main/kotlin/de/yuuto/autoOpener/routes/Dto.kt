package de.yuuto.autoOpener.routes

import kotlinx.serialization.Serializable

@Serializable
data class TokenRequest(val token: String, val role: String? = null, val secret: String? = null)

@Serializable
data class TokenResponse(val token: String)

@Serializable
data class UserInput(val id: String, val name: String, val secret: String? = null)

@Serializable
data class UsersList(val users: List<UserInput>)

@Serializable
data class SingleUserRequest(val user: UserInput)

@Serializable
data class UserRemoveRequest(val userId: String)

@Serializable
data class UserStatusRequest(val userIds: List<String>)

/** [hasSecret] only says whether a secret is stored; the secret and its hash are never returned. */
@Serializable
data class UserStatus(val id: String, val registered: Boolean, val hasSecret: Boolean, val connected: Boolean)

@Serializable
data class UserStatusResponse(val users: List<UserStatus>)

@Serializable
data class SyncResponse(
    val message: String,
    val added: Int,
    val removed: Int,
    val unchanged: Int,
    val processingTimeMs: Long,
)

@Serializable
data class DuplicateIdsResponse(val error: String, val duplicateIds: Set<String>)

@Serializable
data class UserAddedResponse(val message: String, val userId: String, val operation: String)

@Serializable
data class UserRemovedResponse(val message: String, val userId: String)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class HealthResponse(val status: String = "UP", val timestamp: Long = System.currentTimeMillis())
