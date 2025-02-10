package de.yuuto.autoOpener.plugins

import de.yuuto.autoOpener.routes.generateToken
import de.yuuto.autoOpener.routes.messageRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        messageRoutes()
        generateToken()
    }
}