package de.yuuto.autoOpener.dataclass

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

@Serializable
data class User(
    @Contextual @SerialName("_id") val docId: ObjectId = ObjectId(),
    val id: String,
    val name: String,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Serializable
data class UsersList(
    val users: List<User>
)

@Serializable
data class SingleUserRequest(
    val user: User
)

@Serializable
data class UserRemoveRequest(
    val userId: String
)

@Serializable
data class SyncResult(
    val added: Int,
    val removed: Int,
    val unchanged: Int
)
