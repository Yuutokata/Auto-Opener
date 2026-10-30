package de.yuuto.autoOpener.users

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** Stored shape of a user. Field names match the documents written by earlier versions. */
@Serializable
internal data class UserDocument(
    @Contextual @SerialName("_id") val docId: ObjectId? = null,
    val id: String,
    val name: String,
    val lastUpdated: Long,
    val secretHash: String? = null,
)

class MongoUserRepository(uri: String, databaseName: String) : UserRepository, AutoCloseable {
    private val logger = LoggerFactory.getLogger(MongoUserRepository::class.java)

    private val client: MongoClient = MongoClient.create(
        MongoClientSettings.builder()
            .applyToClusterSettings { it.serverSelectionTimeout(10, TimeUnit.SECONDS) }
            .applyConnectionString(ConnectionString(uri))
            .build()
    )
    private val users = client.getDatabase(databaseName).getCollection<UserDocument>("users")

    /** Makes `id` unique. A failure is logged instead of aborting startup so that legacy duplicates cannot take the service down. */
    suspend fun ensureIndexes() {
        try {
            users.createIndex(Indexes.ascending("id"), IndexOptions().unique(true))
        } catch (e: Exception) {
            logger.error("Could not create unique index on users.id; check the collection for duplicate ids", e)
        }
    }

    override suspend fun find(id: String): User? =
        users.find(Filters.eq("id", id)).firstOrNull()?.toUser()

    override suspend fun upsert(id: String, name: String, secretHash: String?): Boolean {
        val updates = buildList {
            add(Updates.set("name", name))
            add(Updates.set("lastUpdated", System.currentTimeMillis()))
            if (secretHash != null) add(Updates.set("secretHash", secretHash))
        }
        val result = users.updateOne(Filters.eq("id", id), Updates.combine(updates), UpdateOptions().upsert(true))
        return result.upsertedId != null
    }

    override suspend fun delete(id: String): Boolean =
        users.deleteOne(Filters.eq("id", id)).deletedCount > 0

    override suspend fun synchronize(incoming: List<Pair<String, String>>): SyncOutcome {
        val existing = users.distinct<String>("id").toList().toSet()
        val incomingIds = incoming.map { it.first }.toSet()
        val toAdd = incoming.filter { it.first !in existing }
        val toRemove = existing.filter { it !in incomingIds }

        toAdd.forEach { (id, name) -> upsert(id, name, null) }
        if (toRemove.isNotEmpty()) users.deleteMany(Filters.`in`("id", toRemove))

        return SyncOutcome(added = toAdd.size, removed = toRemove.size, unchanged = existing.size - toRemove.size)
    }

    override fun close() = client.close()

    private fun UserDocument.toUser() = User(id = id, name = name, lastUpdated = lastUpdated, secretHash = secretHash)
}
