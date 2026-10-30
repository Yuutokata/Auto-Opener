package de.yuuto.autoOpener.users

import com.github.benmanes.caffeine.cache.Caffeine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** Caches positive lookups only, so a newly added user is visible immediately. */
class CachedUserRepository(
    private val delegate: UserRepository,
    ttl: Duration = 5.minutes,
    maxSize: Long = 100_000,
) : UserRepository {
    private val cache = Caffeine.newBuilder().expireAfterWrite(ttl.toJavaDuration()).maximumSize(maxSize).build<String, User>()

    override suspend fun find(id: String): User? {
        cache.getIfPresent(id)?.let { return it }
        return delegate.find(id)?.also { cache.put(id, it) }
    }

    override suspend fun upsert(id: String, name: String, secretHash: String?): Boolean {
        return try {
            delegate.upsert(id, name, secretHash)
        } finally {
            cache.invalidate(id)
        }
    }

    override suspend fun delete(id: String): Boolean {
        return try {
            delegate.delete(id)
        } finally {
            cache.invalidate(id)
        }
    }

    override suspend fun synchronize(incoming: List<Pair<String, String>>): SyncOutcome {
        return try {
            delegate.synchronize(incoming)
        } finally {
            // Invalidate after the write so a concurrent lookup cannot re-cache a user that was just deleted.
            cache.invalidateAll()
        }
    }
}
