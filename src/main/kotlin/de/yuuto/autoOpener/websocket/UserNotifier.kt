package de.yuuto.autoOpener.websocket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class Delivery(val found: Int, val delivered: Int)

/** Delivers a text payload to every live connection of a user, in parallel and with a per-connection timeout. */
class UserNotifier(
    private val registry: ConnectionRegistry,
    private val sendTimeout: Duration = 5.seconds,
) {
    private val logger = LoggerFactory.getLogger(UserNotifier::class.java)

    suspend fun send(userId: String, payload: String): Delivery = coroutineScope {
        val targets = registry.forUser(userId)
        val results = targets.map { connection -> async { deliver(connection, payload) } }.awaitAll()
        Delivery(found = targets.size, delivered = results.count { it })
    }

    private suspend fun deliver(connection: Connection, payload: String): Boolean = try {
        withTimeout(sendTimeout) { connection.sendText(payload) }
        true
    } catch (e: TimeoutCancellationException) {
        logger.warn("Timed out sending to {}", connection)
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Failed to send to {}: {}", connection, e.message)
        false
    }
}
