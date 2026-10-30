package de.yuuto.autoOpener

import de.yuuto.autoOpener.websocket.Connection
import de.yuuto.autoOpener.websocket.ConnectionKind
import de.yuuto.autoOpener.websocket.ConnectionRegistry
import de.yuuto.autoOpener.websocket.KeepAlive
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameType
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KeepAliveTest {
    private val settings = testConfig(mapOf("PING_INTERVAL" to "25", "PONG_TIMEOUT" to "10")).webSocket

    private fun connection(session: FakeSession, now: Long) = Connection(ConnectionKind.USER, USER_ID, session, now)

    @Test
    fun `pings idle connections only after the ping interval`() = runTest {
        var now = 0L
        val session = FakeSession()
        val registry = ConnectionRegistry(3).also { it.register(connection(session, now)) }
        val keepAlive = KeepAlive(registry, settings, backgroundScope) { now }

        now = 24_000
        keepAlive.tick()
        assertTrue(session.sent().isEmpty())

        now = 25_000
        keepAlive.tick()
        assertEquals(FrameType.PING, assertIs<Frame.Ping>(session.sent().single()).frameType)
    }

    @Test
    fun `any inbound frame clears the pending ping`() = runTest {
        var now = 0L
        val session = FakeSession()
        val connection = connection(session, now)
        val registry = ConnectionRegistry(3).also { it.register(connection) }
        val keepAlive = KeepAlive(registry, settings, backgroundScope) { now }

        now = 25_000
        keepAlive.tick()
        connection.markAlive()
        now = 40_000
        keepAlive.tick()
        runCurrent()

        assertEquals(1, registry.size)
    }

    @Test
    fun `closes connections that stay silent after a ping`() = runTest {
        var now = 0L
        val session = FakeSession()
        val registry = ConnectionRegistry(3).also { it.register(connection(session, now)) }
        val keepAlive = KeepAlive(registry, settings, backgroundScope) { now }

        now = 25_000
        keepAlive.tick()
        now = 36_000
        keepAlive.tick()
        runCurrent()

        assertEquals(0, registry.size)
        val frames = session.sent()
        assertIs<Frame.Ping>(frames.first())
        assertIs<Frame.Close>(frames.last())
    }
}
