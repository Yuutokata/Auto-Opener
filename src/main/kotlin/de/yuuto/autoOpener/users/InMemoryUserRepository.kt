package de.yuuto.autoOpener.users

import java.util.concurrent.ConcurrentHashMap

/** Repository without persistence, used by tests. */
class InMemoryUserRepository(private val clock: () -> Long = System::currentTimeMillis) : UserRepository {
    private val users = ConcurrentHashMap<String, User>()

    override suspend fun find(id: String): User? = users[id]

    override suspend fun upsert(id: String, name: String, secretHash: String?): Boolean {
        val existing = users[id]
        users[id] = User(id, name, clock(), secretHash ?: existing?.secretHash)
        return existing == null
    }

    override suspend fun delete(id: String): Boolean = users.remove(id) != null

    override suspend fun synchronize(incoming: List<Pair<String, String>>): SyncOutcome {
        val incomingIds = incoming.map { it.first }.toSet()
        val toAdd = incoming.filter { !users.containsKey(it.first) }
        val toRemove = users.keys.filter { it !in incomingIds }
        toAdd.forEach { (id, name) -> upsert(id, name, null) }
        toRemove.forEach { users.remove(it) }
        return SyncOutcome(added = toAdd.size, removed = toRemove.size, unchanged = users.size - toAdd.size)
    }
}
