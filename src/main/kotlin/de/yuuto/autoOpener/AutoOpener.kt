package de.yuuto.autoOpener

import de.yuuto.autoOpener.plugins.*
import de.yuuto.autoOpener.util.Config
import de.yuuto.autoOpener.util.RedisManager
import de.yuuto.autoOpener.util.WebSocketManager
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*


val redisManager = RedisManager()
val webSocketManager = WebSocketManager(redisManager)


fun main() {
    embeddedServer(
        Netty,
        port = Config.getPort(),
        host = Config.getHost(),
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {

    configureMonitoring()
    configureSerialization()
    configureSecurity()
    configureWebsockets()
    configureRouting()
    configureSecurityHeaders()
}
