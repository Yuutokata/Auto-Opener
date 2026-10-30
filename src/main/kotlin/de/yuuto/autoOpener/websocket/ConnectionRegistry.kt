package de.yuuto.autoOpener.websocket

import java.util.concurrent.ConcurrentHashMap

/** Tracks live connections. Mutations are serialized; reads work on snapshots. */
class ConnectionRegistry(private val maxConnectionsPerUser: Int) {
    private val lock = Any()
    private val byId = ConcurrentHashMap<String, Connection>()
    private val byUser = HashMap<String, ArrayDeque<Connection>>()

    /** Registers [connection] and returns the oldest connections of the same user that exceed the limit. */
    fun register(connection: Connection): List<Connection> = synchronized(lock) {
        byId[connection.id] = connection
        if (connection.kind != ConnectionKind.USER) return emptyList()

        val connections = byUser.getOrPut(connection.subject) { ArrayDeque() }
        connections.addLast(connection)
        val evicted = mutableListOf<Connection>()
        while (connections.size > maxConnectionsPerUser) {
            val oldest = connections.removeFirst()
            byId.remove(oldest.id)
            evicted += oldest
        }
        evicted
    }

    /** Returns true if the connection was still registered. */
    fun remove(connection: Connection): Boolean = synchronized(lock) {
        val removed = byId.remove(connection.id) != null
        if (connection.kind == ConnectionKind.USER) {
            byUser[connection.subject]?.let { connections ->
                connections.remove(connection)
                if (connections.isEmpty()) byUser.remove(connection.subject)
            }
        }
        removed
    }

    fun forUser(userId: String): List<Connection> = synchronized(lock) { byUser[userId]?.toList().orEmpty() }

    fun all(): List<Connection> = byId.values.toList()

    val size: Int get() = byId.size
}
