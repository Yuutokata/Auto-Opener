package de.yuuto.autoOpener.util

import io.ktor.websocket.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class WebsocketHealthManager(
    private val wsManager: WebSocketManager, private val dispatcherProvider: DispatcherProvider
) {
    private val logger = LoggerFactory.getLogger(WebsocketHealthManager::class.java)
    private val activeMonitors = ConcurrentHashMap<String, Job>()

    private val heartbeatInterval = Config.getHeartBeatInterval().seconds

    fun startConnectionMonitor(connectionId: String) {
        activeMonitors[connectionId] = CoroutineScope(dispatcherProvider.heartbeat).launch {
            while (isActive) {
                val session = wsManager.activeConnections[connectionId] ?: break
                try {
                    delay(heartbeatInterval)
                } catch (e: Exception) {
                    logger.error("[$connectionId] Health monitoring failed", e)
                    terminateConnection(connectionId)
                    break
                }
            }
            cleanupMonitor(connectionId)
        }
    }

    private fun terminateConnection(connectionId: String) {
        CoroutineScope(dispatcherProvider.websocket).launch {
            wsManager.activeConnections[connectionId]?.close(
                CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Heartbeat failure: pong timeout")
            )
            wsManager.cleanupConnection(connectionId, wsManager.extractUserId(connectionId))
        }
    }

    fun removeConnection(connectionId: String) {
        activeMonitors.remove(connectionId)?.cancel()
    }

    private fun cleanupMonitor(connectionId: String) {
        activeMonitors.remove(connectionId)
        logger.debug("[$connectionId] Health monitoring stopped")
    }

    fun shutdown() {
        activeMonitors.values.forEach { it.cancel() }
        logger.info("Health manager shutdown complete")
    }
}
