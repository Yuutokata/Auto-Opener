package de.yuuto.autoOpener.util

import com.mongodb.client.model.ReplaceOptions
import de.yuuto.autoOpener.dataclass.SyncResult
import de.yuuto.autoOpener.dataclass.User
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.litote.kmongo.coroutine.CoroutineClient
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.eq
import org.litote.kmongo.reactivestreams.KMongo
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

class MongoClient(private val dispatcherProvider: DispatcherProvider) {
    private val logger = LoggerFactory.getLogger(MongoClient::class.java)
    private val client: CoroutineClient = KMongo.createClient(Config.getMongoDBUri()).coroutine.also {
        MDC.put("event_type", "db_client_init")
        logger.info("MongoDB client created with URI: ${maskConnectionString(Config.getMongoDBUri())}")
        MDC.clear()
    }
    private val database: CoroutineDatabase = client.getDatabase(Config.getMongoDBDatabase()).also {
        MDC.put("event_type", "db_connect")
        MDC.put("db_name", Config.getMongoDBDatabase())
        logger.info("Connected to MongoDB database: ${Config.getMongoDBDatabase()}")
        MDC.clear()
    }
    private val usersCollection = database.getCollection<User>("users").also {
        MDC.put("event_type", "db_collection_init")
        MDC.put("collection_name", "users")
        logger.debug("Users collection initialized")
        MDC.clear()
    }

    private val userCache = ConcurrentHashMap<String, User>()
    private val cacheMutex = Mutex()
    private var cacheInitialized = false

    suspend fun synchronizeUsers(incomingUsers: List<User>): SyncResult = withContext(dispatcherProvider.database) {
        MDC.put("event_type", "user_sync_start")
        MDC.put("incoming_user_count", incomingUsers.size.toString())
        logger.debug("Starting user synchronization")
        MDC.clear()

        val currentUsers = getAllUsersFromDB()
        val currentUserIds = currentUsers.map { it.id }.toSet()
        val incomingUserIds = incomingUsers.map { it.id }.toSet()

        // Check for duplicate IDs in incoming users
        val duplicateIds = incomingUsers.groupBy { it.id }.filter { it.value.size > 1 }.keys

        if (duplicateIds.isNotEmpty()) {
            MDC.put("event_type", "user_sync_warning")
            MDC.put("duplicate_ids", duplicateIds.joinToString(","))
            logger.warn("Found duplicate user IDs in incoming data")
            MDC.clear()
        }

        val usersToAdd = incomingUsers.filter { it.id !in currentUserIds }
        val userIdsToRemove = currentUserIds.filter { it !in incomingUserIds }

        MDC.put("event_type", "user_sync_analysis")
        MDC.put("users_to_add", usersToAdd.size.toString())
        MDC.put("users_to_remove", userIdsToRemove.size.toString())
        logger.debug("Synchronization analysis complete")
        MDC.clear()

        val addedCount = if (usersToAdd.isNotEmpty()) {
            usersToAdd.forEach { addUser(it) }
            usersToAdd.size
        } else 0

        val removedCount = if (userIdsToRemove.isNotEmpty()) {
            userIdsToRemove.forEach { removeUser(it) }
            userIdsToRemove.size
        } else 0

        val unchangedCount = currentUserIds.size - removedCount

        SyncResult(
            added = addedCount, removed = removedCount, unchanged = unchangedCount
        ).also {
            MDC.put("event_type", "user_sync_complete")
            MDC.put("added_count", it.added.toString())
            MDC.put("removed_count", it.removed.toString())
            MDC.put("unchanged_count", it.unchanged.toString())
            logger.info("User synchronization complete")
            MDC.clear()
        }
    }

    suspend fun getAllUsers(): List<User> = withContext(dispatcherProvider.processing) {
        if (!cacheInitialized) {
            MDC.put("event_type", "cache_miss")
            logger.debug("Cache not initialized, refreshing cache")
            MDC.clear()
            return@withContext refreshCache()
        }
        MDC.put("event_type", "cache_hit")
        MDC.put("cache_size", userCache.size.toString())
        logger.debug("Returning users from cache")
        MDC.clear()
        userCache.values.toList()
    }

    private suspend fun getAllUsersFromDB(): List<User> = withContext(dispatcherProvider.database) {
        MDC.put("event_type", "db_fetch_all_users_start")
        logger.debug("Fetching all users from database")
        MDC.clear()
        var userList: List<User> = emptyList()
        try {
            val execTime = measureTimeMillis {
                userList = usersCollection.find().toList()
            }
            MDC.put("event_type", "db_fetch_all_users_success")
            MDC.put("user_count", userList.size.toString())
            MDC.put("duration_ms", execTime.toString())
            logger.debug("Retrieved users from database")
            MDC.clear()
        } catch (e: Exception) {
            MDC.put("event_type", "db_fetch_all_users_error")
            logger.error("Error fetching users from database", e)
            MDC.clear()
            throw e
        }
        userList
    }

    suspend fun refreshCache(): List<User> = withContext(dispatcherProvider.database) {
        MDC.put("event_type", "cache_refresh_start")
        logger.debug("Starting cache refresh")
        MDC.clear()
        cacheMutex.withLock {
            val execTime = measureTimeMillis {
                try {
                    val users = getAllUsersFromDB()
                    userCache.clear()
                    users.forEach { user ->
                        userCache[user.id] = user
                    }
                    cacheInitialized = true
                    MDC.put("event_type", "cache_refresh_success")
                    MDC.put("cached_user_count", users.size.toString())
                    logger.debug("Cache refresh completed")
                    MDC.clear()
                    users
                } catch (e: Exception) {
                    MDC.put("event_type", "cache_refresh_error")
                    logger.error("Failed to refresh cache", e)
                    MDC.clear()
                    throw e
                }
            }
            MDC.put("event_type", "cache_refresh_timing")
            MDC.put("duration_ms", execTime.toString())
            logger.debug("Cache refresh operation timing")
            MDC.clear()
            userCache.values.toList()
        }
    }

    suspend fun userExists(userId: String): Boolean = withContext(dispatcherProvider.processing) {
        // Check cache first if initialized
        if (cacheInitialized && userCache.containsKey(userId)) {
            MDC.put("event_type", "user_exists_check")
            MDC.put("user_id", userId)
            MDC.put("source", "cache")
            MDC.put("result", "true")
            logger.debug("User found in cache")
            MDC.clear()
            return@withContext true
        }

        // Otherwise check database
        withContext(dispatcherProvider.database) {
            MDC.put("event_type", "user_exists_check")
            MDC.put("user_id", userId)
            MDC.put("source", "database")
            logger.debug("Checking if user exists in database")
            MDC.clear()
            try {
                val exists = usersCollection.countDocuments(User::id eq userId) > 0
                MDC.put("event_type", "user_exists_check")
                MDC.put("user_id", userId)
                MDC.put("source", "database")
                MDC.put("result", exists.toString())
                logger.debug("Database check complete")
                MDC.clear()
                exists
            } catch (e: Exception) {
                MDC.put("event_type", "user_exists_check_error")
                MDC.put("user_id", userId)
                MDC.put("source", "database")
                logger.error("Error checking if user exists", e)
                MDC.clear()
                throw e
            }
        }
    }

    suspend fun addUser(user: User): Boolean = withContext(dispatcherProvider.database) {
        val options = ReplaceOptions().upsert(true)
        var success = false

        MDC.put("event_type", "db_add_user_start")
        MDC.put("user_id", user.id)
        logger.debug("Attempting to add/update user")
        MDC.clear()
        try {
            val execTime = measureTimeMillis {
                val result = usersCollection.replaceOne(User::id eq user.id, user, options)
                MDC.put("event_type", "db_add_user_result")
                MDC.put("user_id", user.id)
                MDC.put("modified_count", result.modifiedCount.toString())
                MDC.put("matched_count", result.matchedCount.toString())
                MDC.put("upserted_id", result.upsertedId?.toString() ?: "null")
                logger.debug("MongoDB replaceOne result")
                MDC.clear()

                success = result.modifiedCount > 0 || result.upsertedId != null

                if (success) {
                    cacheMutex.withLock {
                        userCache[user.id] = user
                    }
                    MDC.put("event_type", "cache_update_user")
                    MDC.put("user_id", user.id)
                    logger.debug("User added/updated in cache")
                    MDC.clear()
                } else {
                    MDC.put("event_type", "db_add_user_fail")
                    MDC.put("user_id", user.id)
                    logger.error("Failed to add user to database (no modification or upsert)")
                    MDC.clear()
                }
            }
            MDC.put("event_type", "db_add_user_timing")
            MDC.put("user_id", user.id)
            MDC.put("duration_ms", execTime.toString())
            MDC.put("status", if (success) "success" else "failure")
            logger.debug("Add user operation timing")
            MDC.clear()
            success
        } catch (e: Exception) {
            MDC.put("event_type", "db_add_user_error")
            MDC.put("user_id", user.id)
            logger.error("Exception occurred while adding user", e)
            MDC.clear()
            throw e
        }
    }

    suspend fun removeUser(userId: String): Pair<Boolean, String> = withContext(dispatcherProvider.database) {
        MDC.put("event_type", "db_remove_user_start")
        MDC.put("user_id", userId)
        logger.debug("Attempting to remove user")
        MDC.clear()

        // First check if user exists
        if (!userExists(userId)) {
            MDC.put("event_type", "db_remove_user_fail")
            MDC.put("user_id", userId)
            MDC.put("reason", "User does not exist")
            logger.warn("Attempted to remove non-existent user")
            MDC.clear()
            return@withContext Pair(false, "User does not exist")
        }

        var result: Pair<Boolean, String> = Pair(false, "Unknown error")

        try {
            val execTime = measureTimeMillis {
                val deleteResult = usersCollection.deleteOne(User::id eq userId)
                val success = deleteResult.deletedCount > 0

                MDC.put("event_type", "db_remove_user_result")
                MDC.put("user_id", userId)
                MDC.put("deleted_count", deleteResult.deletedCount.toString())
                logger.debug("MongoDB deleteOne result")
                MDC.clear()

                if (success) {
                    cacheMutex.withLock {
                        userCache.remove(userId)
                    }
                    MDC.put("event_type", "cache_remove_user")
                    MDC.put("user_id", userId)
                    logger.info("User removed from cache")
                    MDC.clear()
                    result = Pair(true, "User removed successfully")
                } else {
                    MDC.put("event_type", "db_remove_user_fail")
                    MDC.put("user_id", userId)
                    MDC.put("reason", "Database operation failed despite existing check")
                    logger.error("Failed to remove user despite existing check")
                    MDC.clear()
                    result = Pair(false, "Database operation failed")
                }
            }
            MDC.put("event_type", "db_remove_user_timing")
            MDC.put("user_id", userId)
            MDC.put("duration_ms", execTime.toString())
            MDC.put("status", if (result.first) "success" else "failure")
            logger.debug("Remove user operation timing")
            MDC.clear()
            result
        } catch (e: Exception) {
            MDC.put("event_type", "db_remove_user_error")
            MDC.put("user_id", userId)
            logger.error("Exception occurred while removing user", e)
            MDC.clear()
            throw e
        }
    }

    fun userExistsInCache(userId: String): Boolean {
        val exists = cacheInitialized && userCache.containsKey(userId)
        MDC.put("event_type", "cache_check_user")
        MDC.put("user_id", userId)
        MDC.put("result", exists.toString())
        logger.debug("Checked if user exists in cache")
        MDC.clear()
        return exists
    }

    private fun maskConnectionString(uri: String): String {
        val regex = "(mongodb(?:\\+srv)?)://([^:]+):([^@]+)@".toRegex()
        return uri.replace(regex, "$1://$2:****@")
    }

    suspend fun close() = withContext(dispatcherProvider.database) {
        MDC.put("event_type", "db_client_close_start")
        logger.info("Closing MongoDB client connection...")
        MDC.clear()
        try {
            client.close()
            MDC.put("event_type", "db_client_close_success")
            logger.info("MongoDB client connection closed successfully")
            MDC.clear()
        } catch (e: Exception) {
            MDC.put("event_type", "db_client_close_error")
            logger.error("Error closing MongoDB client connection", e)
            MDC.clear()
        }
    }

}
