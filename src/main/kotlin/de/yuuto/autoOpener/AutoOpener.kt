package de.yuuto.autoOpener

import de.yuuto.autoOpener.plugins.*
import de.yuuto.autoOpener.util.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory


class DependencyProvider {
    val dispatcherProvider = DispatcherProvider()
    val mongoClient = MongoClient(dispatcherProvider)
    var webSocketManager: WebSocketManager = WebSocketManager(dispatcherProvider)
    val botConnectionManager: BotConnectionManager = BotConnectionManager(dispatcherProvider, webSocketManager)
}

val dependencyProvider = DependencyProvider()

fun main() {
    val logger = LoggerFactory.getLogger("Main")

    // Add shutdown hook to gracefully terminate resources
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Application shutdown initiated...")

        try {
            println("Closing WebSocket connections...")
            runBlocking {
                dependencyProvider.webSocketManager.activeConnections.forEach { (connectionId, session) ->
                    session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
                    dependencyProvider.webSocketManager.cleanupConnection(connectionId)
                }


                dependencyProvider.webSocketManager.shutdown()

                dependencyProvider.botConnectionManager.shutdown()

                // Close MongoDB connections
                logger.info("Closing MongoDB connections...")
                dependencyProvider.mongoClient.close()

                // Close dispatcher resources
                logger.info("Closing dispatcher resources...")
                dependencyProvider.dispatcherProvider.close()

                logger.info("Shutdown complete")
            }
        } catch (e: Exception) {
            System.err.println("Error during shutdown: ${e.message}")
            e.printStackTrace()
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
