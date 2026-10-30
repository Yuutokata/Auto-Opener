package de.yuuto.autoOpener.users

/** A Discord user known to the server. [secretHash] is the SHA-256 hex digest of the user's extension secret. */
data class User(
    val id: String,
    val name: String,
    val lastUpdated: Long,
    val secretHash: String? = null,
)

/** Result of a bulk synchronization. */
data class SyncOutcome(val added: Int, val removed: Int, val unchanged: Int)

/** Discord snowflakes are 15 to 20 digits long; the same format is required in JWT claims. */
val userIdPattern = Regex("^[0-9]{15,20}$")
