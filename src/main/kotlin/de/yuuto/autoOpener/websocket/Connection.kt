package de.yuuto.autoOpener.websocket

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class ConnectionKind { USER, BOT }

/** One live WebSocket. [subject] is the Discord user id for users and a constant label for bots. */
class Connection(
    val kind: ConnectionKind,
    val subject: String,
    val session: WebSocketSession,
    now: Long,
) {
    val id: String = UUID.randomUUID().toString()
    val lastPingSentAt = AtomicLong(now)

    /** Time the oldest unanswered ping was sent, or 0 when none is outstanding. */
    val pendingPingSince = AtomicLong(0)

    /** Any inbound frame proves the peer is alive. */
    fun markAlive() = pendingPingSince.set(0)

    suspend fun sendText(text: String) = session.outgoing.send(Frame.Text(text))

    /** Sends a close frame and cancels the session if the peer does not finish the handshake within [grace]. */
    suspend fun close(reason: CloseReason, grace: Duration = 2.seconds) {
        runCatching { session.close(reason) }
        delay(grace)
        session.cancel()
    }

    override fun toString() = "${kind.name.lowercase()}:$subject/$id"
}
