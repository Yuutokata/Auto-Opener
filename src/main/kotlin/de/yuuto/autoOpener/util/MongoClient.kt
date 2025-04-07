package de.yuuto.autoOpener.util

import com.mongodb.client.model.ReplaceOptions
import de.yuuto.autoOpener.dataclass.SyncResult
import de.yuuto.autoOpener.dataclass.User
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.eq
import org.litote.kmongo.reactivestreams.KMongo
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

class MongoClient(private val dispatcherProvider: DispatcherProvider) {
    private val logger = LoggerFactory.getLogger(MongoClient::class.java)
    private val client = KMongo.createClient(Config.getMongoDBUri()).also {
        logger.info("MongoDB client created with URI: ${maskConnectionString(Config.getMongoDBUri())}")
    }
    private val database = client.coroutine.getDatabase(Config.getMongoDBDatabase()).also {
        logger.info("Connected to MongoDB database: ${Config.getMongoDBDatabase()}")
    }
    private val usersCollection = database.getCollection<User>("users").also {
        logger.debug("Users collection initialized")
    }

    private val userCache = ConcurrentHashMap<String, User>()
    private val cacheMutex = Mutex()
    private var cacheInitialized = false

    suspend fun synchronizeUsers(incomingUsers: List<User>): SyncResult = withContext(dispatcherProvider.database) {
        logger.debug("Starting user synchronization with ${incomingUsers.size} incoming users")
        val currentUsers = getAllUsersFromDB()
        val currentUserIds = currentUsers.map { it.id }.toSet()
        val incomingUserIds = incomingUsers.map { it.id }.toSet()

        // Check for duplicate IDs in incoming users
        val duplicateIds = incomingUsers.groupBy { it.id }.filter { it.value.size > 1 }.keys

        if (duplicateIds.isNotEmpty()) {
            logger.warn("Found duplicate user IDs in incoming data: $duplicateIds")
        }

        val usersToAdd = incomingUsers.filter { it.id !in currentUserIds }
        val userIdsToRemove = currentUserIds.filter { it !in incomingUserIds }

        logger.debug("Synchronization analysis: ${usersToAdd.size} users to add, ${userIdsToRemove.size} users to remove")

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
        )
    }

    suspend fun getAllUsers(): List<User> = withContext(dispatcherProvider.processing) {
        if (!cacheInitialized) {
            logger.debug("Cache not initialized, refreshing cache")
            return@withContext refreshCache()
        }
        logger.debug("Returning ${userCache.size} users from cache")
        userCache.values.toList()
    }

    private suspend fun getAllUsersFromDB(): List<User> = withContext(dispatcherProvider.database) {
        logger.debug("Fetching all users from database")
        var userList: List<User> = emptyList()
        try {
            val execTime = measureTimeMillis {
                userList = usersCollection.find().toList()
            }
            logger.debug("Retrieved ${userList.size} users from database in ${execTime}ms")
        } catch (e: Exception) {
            logger.error("Error fetching users from database", e)
            throw e
        }
        userList
    }

    suspend fun refreshCache(): List<User> = withContext(dispatcherProvider.database) {
        logger.debug("Starting cache refresh")
        cacheMutex.withLock {
            val execTime = measureTimeMillis {
                try {
                    val users = getAllUsersFromDB()
                    userCache.clear()
                    users.forEach { user ->
                        userCache[user.id] = user
                    }
                    cacheInitialized = true
                    logger.debug("Cache refresh completed, ${users.size} users cached")
                    users
                } catch (e: Exception) {
                    logger.error("Failed to refresh cache", e)
                    throw e
                }
            }
            logger.debug("Cache refresh operation took ${execTime}ms")
            userCache.values.toList()
        }
    }

    suspend fun userExists(userId: String): Boolean = withContext(dispatcherProvider.processing) {
        // Check cache first if initialized
        if (cacheInitialized && userCache.containsKey(userId)) {
            logger.debug("User $userId found in cache")
            return@withContext true
        }

        // Otherwise check database
        withContext(dispatcherProvider.database) {
            logger.debug("Checking if user $userId exists in database")
            try {
                val exists = usersCollection.countDocuments(User::id eq userId) > 0
                logger.debug("User $userId exists in database: $exists")
                exists
            } catch (e: Exception) {
                logger.error("Error checking if user $userId exists", e)
                throw e
            }
        }
    }

    suspend fun addUser(user: User): Boolean = withContext(dispatcherProvider.database) {
        val options = ReplaceOptions().upsert(true)
        var success = false

        logger.debug("Attempting to add/update user with ID: ${user.id}")
        try {
            val execTime = measureTimeMillis {
                val result = usersCollection.replaceOne(User::id eq user.id, user, options)
                logger.debug(
                    "MongoDB result: modifiedCount={}, matchedCount={}, upsertedId={}",
                    result.modifiedCount,
                    result.matchedCount,
                    result.upsertedId
                )

                success = result.modifiedCount > 0 || result.upsertedId != null

                if (success) {
                    cacheMutex.withLock {
                        userCache[user.id] = user
                    }
                    logger.debug("User ${user.id} added to cache")
                } else {
                    logger.error("Failed to add user ${user.id} to database")
                }
            }
            logger.debug("Add user operation for ${user.id} completed in ${execTime}ms")
            success
        } catch (e: Exception) {
            logger.error("Exception occurred while adding user ${user.id}", e)
            throw e
        }
    }

    suspend fun removeUser(userId: String): Pair<Boolean, String> = withContext(dispatcherProvider.database) {
        logger.debug("Attempting to remove user with ID: $userId")
        // First check if user exists
        if (!userExists(userId)) {
            logger.warn("Attempted to remove non-existent user: $userId")
            return@withContext Pair(false, "User does not exist")
        }

        var result: Pair<Boolean, String> = Pair(false, "Unknown error")

        try {
            val execTime = measureTimeMillis {
                val deleteResult = usersCollection.deleteOne(User::id eq userId)
                val success = deleteResult.deletedCount > 0

                if (success) {
                    cacheMutex.withLock {
                        userCache.remove(userId)
                    }
                    logger.info("User $userId removed successfully")
                    result = Pair(true, "User removed successfully")
                } else {
                    logger.error("Failed to remove user $userId despite existing check")
                    result = Pair(false, "Database operation failed")
                }
            }
            logger.debug("Remove user operation for $userId completed in ${execTime}ms")
            result
        } catch (e: Exception) {
            logger.error("Exception occurred while removing user $userId", e)
            throw e
        }
    }

    fun userExistsInCache(userId: String): Boolean {
        val exists = cacheInitialized && userCache.containsKey(userId)
        logger.debug("Check if user $userId exists in cache: $exists")
        return exists
    }

    private fun maskConnectionString(uri: String): String {
        val regex = "(mongodb://|mongodb\\+srv://)([^:]+):([^@]+)@".toRegex()
        return uri.replace(regex, "$1$2:****@")
    }

    fun close() {
        logger.info("Closing MongoDB client connection...")
        try {
            client.close()
            logger.info("MongoDB client connection closed successfully")
        } catch (e: Exception) {
            logger.error("Error closing MongoDB client connection: ${e.message}", e)
        }
    }

}
