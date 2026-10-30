package de.yuuto.autoOpener

import de.yuuto.autoOpener.auth.JwtService
import de.yuuto.autoOpener.auth.ServiceTokens
import de.yuuto.autoOpener.auth.configureAuthentication
import de.yuuto.autoOpener.config.AppConfig
import de.yuuto.autoOpener.config.ConfigurationException
import de.yuuto.autoOpener.plugins.configureHttp
import de.yuuto.autoOpener.plugins.configureRateLimit
import de.yuuto.autoOpener.routes.healthRoutes
import de.yuuto.autoOpener.routes.messageRoutes
import de.yuuto.autoOpener.routes.tokenRoutes
import de.yuuto.autoOpener.routes.userRoutes
import de.yuuto.autoOpener.users.CachedUserRepository
import de.yuuto.autoOpener.users.MongoUserRepository
import de.yuuto.autoOpener.users.UserRepository
import de.yuuto.autoOpener.websocket.BotMessageHandler
import de.yuuto.autoOpener.websocket.ConnectionManager
import de.yuuto.autoOpener.websocket.ConnectionRegistry
import de.yuuto.autoOpener.websocket.KeepAlive
import de.yuuto.autoOpener.websocket.UserNotifier
import de.yuuto.autoOpener.websocket.configureWebSockets
import de.yuuto.autoOpener.websocket.webSocketRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

fun main() {
    val config = try {
        AppConfig.fromEnvironment()
    } catch (e: ConfigurationException) {
        LoggerFactory.getLogger("Main").error("Invalid configuration: {}", e.message)
        exitProcess(1)
    }
    embeddedServer(Netty, port = config.port, host = config.host) { module(config) }.start(wait = true)
}

/** Wires the application. Pass [users] to run without MongoDB and [registry] to observe connections, for example in tests. */
fun Application.module(
    config: AppConfig,
    users: UserRepository? = null,
    registry: ConnectionRegistry = ConnectionRegistry(config.webSocket.maxConnectionsPerUser),
) {
    val logger = LoggerFactory.getLogger("Application")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val mongo = if (users == null) MongoUserRepository(config.mongoUri, config.mongoDatabase) else null
    val userRepository = users ?: CachedUserRepository(requireNotNull(mongo))
    mongo?.let { scope.launch { it.ensureIndexes() } }

    val jwtService = JwtService(config.jwt)
    val serviceTokens = ServiceTokens(config.serviceTokens)

    val notifier = UserNotifier(registry)
    val connections = ConnectionManager(registry, BotMessageHandler(notifier), scope)
    KeepAlive(registry, config.webSocket, scope).start()

    configureHttp()
    configureAuthentication(jwtService, userRepository, serviceTokens)
    configureRateLimit(config.rateLimits, config.clientIpHeader)
    configureWebSockets(config.webSocket)

    routing {
        healthRoutes()
        tokenRoutes(jwtService, userRepository, serviceTokens, config.requireUserSecret)
        userRoutes(userRepository, connections)
        messageRoutes(notifier)
        webSocketRoutes(connections)
    }

    monitor.subscribe(ApplicationStopping) {
        logger.info("Shutting down: closing {} connections", registry.size)
        runBlocking {
            withTimeoutOrNull(5.seconds) {
                connections.closeAll(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
            }
        }
        scope.cancel()
    }
    monitor.subscribe(ApplicationStopped) { mongo?.close() }

    if (config.clientIpHeader == null) {
        logger.warn("CLIENT_IP_HEADER is not set: behind a reverse proxy all clients share one rate limit bucket")
    }
    if (config.jwt.secret.length < 32) {
        logger.warn("JWT_SECRET is shorter than 32 characters; use a long random value")
    }
    logger.info(
        "Auto-Opener started (user secret required: {}, max connections per user: {})",
        config.requireUserSecret, config.webSocket.maxConnectionsPerUser,
    )
}
