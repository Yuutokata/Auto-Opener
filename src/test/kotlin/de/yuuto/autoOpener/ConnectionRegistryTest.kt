package de.yuuto.autoOpener

import de.yuuto.autoOpener.websocket.Connection
import de.yuuto.autoOpener.websocket.ConnectionKind
import de.yuuto.autoOpener.websocket.ConnectionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionRegistryTest {
    private fun user(id: String) = Connection(ConnectionKind.USER, id, FakeSession(), 0)
    private fun bot() = Connection(ConnectionKind.BOT, "service", FakeSession(), 0)

    @Test
    fun `evicts the oldest connections beyond the limit`() {
        val registry = ConnectionRegistry(2)
        val first = user(USER_ID)
        val second = user(USER_ID)
        val third = user(USER_ID)

        assertTrue(registry.register(first).isEmpty())
        assertTrue(registry.register(second).isEmpty())
        assertEquals(listOf(first), registry.register(third))
        assertEquals(listOf(second, third), registry.forUser(USER_ID))
        assertEquals(2, registry.size)
    }

    @Test
    fun `limits are per user and bots are never evicted`() {
        val registry = ConnectionRegistry(1)
        registry.register(user(USER_ID))
        registry.register(user(OTHER_USER_ID))
        registry.register(bot())
        registry.register(bot())

        assertEquals(1, registry.forUser(USER_ID).size)
        assertEquals(1, registry.forUser(OTHER_USER_ID).size)
        assertEquals(4, registry.size)
    }

    @Test
    fun `remove reports whether the connection was still registered`() {
        val registry = ConnectionRegistry(3)
        val connection = user(USER_ID)
        registry.register(connection)

        assertTrue(registry.remove(connection))
        assertFalse(registry.remove(connection))
        assertTrue(registry.forUser(USER_ID).isEmpty())
    }
}
