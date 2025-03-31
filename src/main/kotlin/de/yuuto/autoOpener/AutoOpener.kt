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
    var redisManager: RedisManager
    var webSocketManager: WebSocketManager = WebSocketManager(dispatcherProvider)

    init {
        redisManager = RedisManager(dispatcherProvider, webSocketManager)
        webSocketManager.setRedisManager(redisManager)
    }
}

val dependencyProvider = DependencyProvider()

fun main() {
    val logger = LoggerFactory.getLogger("Main")

    // Add shutdown hook to gracefully terminate resources
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Application shutdown initiated...")

        try {
            println("Closing WebSocket connections...")
            dependencyProvider.webSocketManager.activeConnections.forEach { (connectionId, session) ->
                runBlocking {
                    session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
                    val userId = dependencyProvider.webSocketManager.extractUserId(connectionId)
                    dependencyProvider.webSocketManager.cleanupConnection(connectionId, userId)
                }
            }

            dependencyProvider.webSocketManager.shutdown()

            // Close Redis connections
            logger.info("Closing Redis connections...")
            dependencyProvider.redisManager.shutdown()

            // Close MongoDB connections
            logger.info("Closing MongoDB connections...")
            dependencyProvider.mongoClient.close()

            // Close dispatcher resources
            logger.info("Closing dispatcher resources...")
            dependencyProvider.dispatcherProvider.close()

            logger.info("Shutdown complete")
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
        dependencyProvider.dispatcherProvider, dependencyProvider.redisManager, dependencyProvider.webSocketManager
    )
    configureRouting(
        dependencyProvider.dispatcherProvider, dependencyProvider.mongoClient, dependencyProvider.redisManager
    )
    configureSecurityHeaders()
}
