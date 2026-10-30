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
        cache.invalidate(id)
        return delegate.upsert(id, name, secretHash)
    }

    override suspend fun delete(id: String): Boolean {
        cache.invalidate(id)
        return delegate.delete(id)
    }

    override suspend fun synchronize(incoming: List<Pair<String, String>>): SyncOutcome {
        cache.invalidateAll()
        return delegate.synchronize(incoming)
    }
}
