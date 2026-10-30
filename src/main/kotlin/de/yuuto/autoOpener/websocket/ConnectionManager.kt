package de.yuuto.autoOpener.websocket

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/** Owns the lifecycle of user and bot sockets: register, read until closed, unregister. */
class ConnectionManager(
    val registry: ConnectionRegistry,
    private val botHandler: BotMessageHandler,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(ConnectionManager::class.java)

    suspend fun serveUser(session: WebSocketSession, userId: String) {
        serve(Connection(ConnectionKind.USER, userId, session, clock())) { }
    }

    suspend fun serveBot(session: WebSocketSession) {
        serve(Connection(ConnectionKind.BOT, BOT_SUBJECT, session, clock())) { text ->
            botHandler.handle(this, text)
        }
    }

    /** Closes every live connection of [userId], for example after the user was removed. */
    fun disconnectUser(userId: String, reason: CloseReason) {
        registry.forUser(userId).forEach { connection ->
            registry.remove(connection)
            scope.launch { connection.close(reason) }
        }
    }

    /** Closes all connections during shutdown and waits for the close handshakes. */
    suspend fun closeAll(reason: CloseReason) {
        registry.all().map { connection ->
            registry.remove(connection)
            scope.launch { connection.close(reason, grace = 1.seconds) }
        }.joinAll()
    }

    private suspend fun serve(connection: Connection, onText: suspend Connection.(String) -> Unit) {
        val evicted = registry.register(connection)
        logger.info("Connected {} ({} live connections)", connection, registry.size)
        evicted.forEach { old ->
            logger.info("Closing {} because {} exceeds the connection limit", old, connection)
            scope.launch { old.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Connection limit reached")) }
        }

        try {
            readFrames(connection, onText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Connection {} failed: {}", connection, e.message)
        } finally {
            if (registry.remove(connection)) logger.info("Disconnected {} ({} live connections)", connection, registry.size)
        }
    }

    private suspend fun readFrames(connection: Connection, onText: suspend Connection.(String) -> Unit) {
        for (frame in connection.session.incoming) {
            connection.markAlive()
            when (frame) {
                is Frame.Ping -> connection.session.outgoing.send(Frame.Pong(frame.data))
                is Frame.Text -> connection.onText(frame.readText())
                is Frame.Close -> {
                    val reason = frame.readReason() ?: CloseReason(CloseReason.Codes.NORMAL, "Client closed")
                    runCatching { connection.session.close(reason) }
                    return
                }

                else -> Unit
            }
        }
    }

    companion object {
        const val BOT_SUBJECT = "service"
    }
}
