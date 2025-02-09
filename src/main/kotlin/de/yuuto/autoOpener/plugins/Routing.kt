package de.yuuto.autoOpener.plugins

// import com.example.routes.messageRoutes
import de.yuuto.autoOpener.routes.messageRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        messageRoutes()
    }
}