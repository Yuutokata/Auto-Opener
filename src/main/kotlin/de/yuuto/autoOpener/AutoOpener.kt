package de.yuuto.autoOpener

import de.yuuto.autoOpener.plugins.*
import de.yuuto.autoOpener.util.Config
import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.MongoClient
import de.yuuto.autoOpener.util.WebSocketManager
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.time.Duration.Companion.seconds


class DependencyProvider {
    val dispatcherProvider = DispatcherProvider()
    val mongoClient = MongoClient(dispatcherProvider)
    var webSocketManager: WebSocketManager = WebSocketManager(dispatcherProvider)
}

val dependencyProvider = DependencyProvider()

fun main() {
    val logger = LoggerFactory.getLogger("Main")

    // Add shutdown hook to gracefully terminate resources
    Runtime.getRuntime().addShutdownHook(Thread {
        MDC.put("event_type", "shutdown_initiated")
        logger.info("Application shutdown initiated...")
        MDC.clear()

        // Use runBlocking for the shutdown sequence, but be mindful of potential deadlocks
        // Launch cleanup operations concurrently within runBlocking if possible
        runBlocking {
            try {
                MDC.put("event_type", "shutdown_progress")
                logger.info("Closing WebSocket connections...")
                MDC.clear()

                // Collect connections to close to avoid ConcurrentModificationException
                val connectionsToClose = dependencyProvider.webSocketManager.activeConnections.toList()
                connectionsToClose.forEach { (connectionId, session) ->
                    launch(dependencyProvider.dispatcherProvider.websocket) { // Launch cleanup in appropriate context
                         try {
                            MDC.put("event_type", "connection_close_request")
                            MDC.put("connection_id", connectionId)
                            MDC.put("reason", "Server shutting down")
                            logger.info("Requesting close for connection $connectionId")
                            MDC.clear()
                            session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
                             dependencyProvider.webSocketManager.cleanupConnection(connectionId, dependencyProvider.webSocketManager.determineCloseStatus(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down")))
                         } catch (e: Exception) {
                             MDC.put("event_type", "connection_close_request_error")
                             MDC.put("connection_id", connectionId)
                             logger.error("Error closing connection $connectionId during shutdown", e)
                             MDC.clear()
                             try {
                                 dependencyProvider.webSocketManager.cleanupConnection(connectionId, "shutdown_close_error" to "Error during session close: ${e.message}")
                             } catch (cleanupError: Exception) {
                                  MDC.put("event_type", "connection_cleanup_error")
                                  MDC.put("connection_id", connectionId)
                                  logger.error("Error cleaning up connection $connectionId after close failure", cleanupError)
                                  MDC.clear()
                             }
                         }
                    }
                }


                // Shutdown manager (stops monitoring, etc.)
                dependencyProvider.webSocketManager.shutdown() // This logs internally

                // Close MongoDB connections
                MDC.put("event_type", "shutdown_progress")
                logger.info("Closing MongoDB connections...")
                MDC.clear()
                dependencyProvider.mongoClient.close() // This logs internally

                // Close dispatcher resources
                MDC.put("event_type", "shutdown_progress")
                logger.info("Closing dispatcher resources...")
                MDC.clear()
                dependencyProvider.dispatcherProvider.close() // This logs internally

                MDC.put("event_type", "shutdown_complete")
                logger.info("Shutdown complete")
                MDC.clear()
            } catch (e: Exception) {
                MDC.put("event_type", "shutdown_error")
                MDC.put("error_message", e.message ?: "Unknown shutdown error")
                logger.error("Error during shutdown block", e)
                MDC.clear()
            }
        } // End runBlocking
    })

    embeddedServer(
        Netty, port = Config.getPort(), host = Config.getHost(), module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    configureMonitoring()
    configureSerialization()
    configureSecurity()
    configureWebsockets(
        dependencyProvider.dispatcherProvider, dependencyProvider.webSocketManager
    )
    configureRouting(
        dependencyProvider.dispatcherProvider, dependencyProvider.mongoClient, dependencyProvider.webSocketManager
    )
    configureSecurityHeaders()
}
