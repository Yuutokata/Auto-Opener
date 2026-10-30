package de.yuuto.autoOpener.websocket

import de.yuuto.autoOpener.config.WebSocketSettings
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Application-level keep-alive. Pings every [WebSocketSettings.pingInterval] keep idle connections open through
 * proxies (and keep Chrome extension service workers alive); a connection that stays silent for longer than
 * [WebSocketSettings.pongTimeout] after a ping is closed. Any inbound frame counts as proof of life.
 */
class KeepAlive(
    private val registry: ConnectionRegistry,
    private val settings: WebSocketSettings,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(KeepAlive::class.java)

    fun start(): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Keep-alive pass failed", e)
            }
            delay(settings.healthCheckInterval)
        }
    }

    /** One pass over all connections. Visible for tests. */
    suspend fun tick() {
        val now = clock()
        for (connection in registry.all()) {
            val pendingSince = connection.pendingPingSince.get()
            when {
                pendingSince != 0L && now - pendingSince > settings.pongTimeout.inWholeMilliseconds -> {
                    logger.info("Closing {}: no response to ping", connection)
                    registry.remove(connection)
                    scope.launch { connection.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Ping timeout")) }
                }

                pendingSince == 0L && now - connection.lastPingSentAt.get() >= settings.pingInterval.inWholeMilliseconds ->
                    ping(connection, now)
            }
        }
    }

    private suspend fun ping(connection: Connection, now: Long) {
        try {
            connection.session.outgoing.send(Frame.Ping(ByteArray(0)))
            connection.lastPingSentAt.set(now)
            connection.pendingPingSince.set(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info("Closing {}: ping failed ({})", connection, e.message)
            registry.remove(connection)
            scope.launch { connection.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Ping failed")) }
        }
    }
}
