package de.yuuto.autoOpener

import de.yuuto.autoOpener.plugins.*
import de.yuuto.autoOpener.plugins.startMetricsServer
import de.yuuto.autoOpener.util.Config
import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.MongoClient
import de.yuuto.autoOpener.util.WebSocketManager
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
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

    // Start metrics server on a separate port
    startMetricsServer(Config.getHost(), Config.getMetricsPort())
    logger.info("Metrics server started on port ${Config.getMetricsPort()}")

    // Add shutdown hook to gracefully terminate resources
    Runtime.getRuntime().addShutdownHook(Thread {
        MDC.put("event_type", "shutdown_initiated")
        logger.info("Application shutdown initiated...")
        MDC.clear()

        // Use runBlocking for the shutdown sequence
        runBlocking {
            try {
                // Step 1: Close WebSocket connections
                MDC.put("event_type", "shutdown_progress")
                logger.info("Closing WebSocket connections...")
                MDC.clear()

                // Collect connections to close to avoid ConcurrentModificationException
                val connectionsToClose = dependencyProvider.webSocketManager.activeConnections.toList()
                var successCount = 0
                var failCount = 0

                // Close connections with a supervisor job to handle failures
                val closeJob = SupervisorJob()
                val closeScope = CoroutineScope(closeJob + Dispatchers.IO)

                connectionsToClose.forEach { (connectionId, session) ->
                    closeScope.launch {
                        try {
                            MDC.put("event_type", "connection_close_request")
                            MDC.put("connection_id", connectionId)
                            logger.info("Requesting close for connection $connectionId")
                            MDC.clear()

                            withTimeoutOrNull(5000) { // 5 second timeout per connection
                                session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
                                dependencyProvider.webSocketManager.cleanupConnection(
                                    connectionId, 
                                    "shutdown" to "Server shutting down"
                                )
                            }
                            successCount++
                        } catch (e: Exception) {
                            failCount++
                            MDC.put("event_type", "connection_close_error")
                            MDC.put("connection_id", connectionId)
                            logger.error("Error closing connection during shutdown", e)
                            MDC.clear()

                            // Try to force cleanup
                            try {
                                dependencyProvider.webSocketManager.cleanupConnection(
                                    connectionId, "shutdown_error" to "Error during shutdown"
                                )
                            } catch (cleanupError: Exception) {
                                logger.error("Failed to clean up connection $connectionId", cleanupError)
                            }
                        }
                    }
                }

                // Wait for connections to close with timeout
                withTimeoutOrNull(10000) { // 10 second timeout for all connections
                    closeJob.children.forEach { it.join() }
                }
                closeJob.cancel() // Cancel any remaining jobs

                // Log connection close results
                MDC.put("event_type", "connections_close_summary")
                MDC.put("success_count", successCount.toString())
                MDC.put("fail_count", failCount.toString())
                MDC.put("total_count", connectionsToClose.size.toString())
                logger.info("WebSocket connection close summary: $successCount succeeded, $failCount failed")
                MDC.clear()

                // Step 2: Shutdown WebSocketManager
                MDC.put("event_type", "shutdown_progress")
                logger.info("Shutting down WebSocketManager...")
                MDC.clear()
                withTimeoutOrNull(5000) {
                    dependencyProvider.webSocketManager.shutdown()
                }

                // Step 3: Close MongoDB connections
                MDC.put("event_type", "shutdown_progress")
                logger.info("Closing MongoDB connections...")
                MDC.clear()
                withTimeoutOrNull(5000) {
                    dependencyProvider.mongoClient.close()
                }

                // Step 4: Close dispatcher resources
                MDC.put("event_type", "shutdown_progress")
                logger.info("Closing dispatcher resources...")
                MDC.clear()
                withTimeoutOrNull(5000) {
                    dependencyProvider.dispatcherProvider.close()
                }

                MDC.put("event_type", "shutdown_complete")
                logger.info("Shutdown complete")
                MDC.clear()
            } catch (e: Exception) {
                MDC.put("event_type", "shutdown_error")
                MDC.put("error_message", e.message ?: "Unknown shutdown error")
                logger.error("Error during shutdown", e)
                MDC.clear()
            }
        }
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
