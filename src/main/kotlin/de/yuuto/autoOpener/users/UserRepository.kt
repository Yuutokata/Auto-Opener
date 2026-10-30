package de.yuuto.autoOpener.users

interface UserRepository {
    suspend fun find(id: String): User?

    /**
     * Creates or updates a user. A null [secretHash] keeps the stored hash of an existing user.
     * Returns true if the user was newly created.
     */
    suspend fun upsert(id: String, name: String, secretHash: String?): Boolean

    /** Returns true if a user was deleted. */
    suspend fun delete(id: String): Boolean

    /** Adds users that are missing and removes users that are absent from [incoming]; existing users stay untouched. */
    suspend fun synchronize(incoming: List<Pair<String, String>>): SyncOutcome
}
