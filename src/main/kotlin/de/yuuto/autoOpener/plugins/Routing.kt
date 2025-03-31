package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.routes.generateToken
import de.yuuto.autoOpener.routes.receiveUsers
import de.yuuto.autoOpener.routes.messageRoutes
import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.MongoClient
import de.yuuto.autoOpener.util.RedisManager
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting(dispatcherProvider: DispatcherProvider, mongoClient: MongoClient, redisManager: RedisManager) {
    routing {
        messageRoutes(dispatcherProvider, redisManager)
        generateToken(dispatcherProvider, mongoClient)
        receiveUsers(dispatcherProvider, mongoClient)
    }
}