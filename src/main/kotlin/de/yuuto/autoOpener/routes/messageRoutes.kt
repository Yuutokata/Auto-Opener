package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.util.Config
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.messageRoutes() {
    get("/send_message") {
        val receiverID = call.queryParameters["user_id"]
        val message = call.queryParameters["message"]

        val token = call.request.headers["Authorization"]

        if (token == null || token != Config.getBotToken()) {
            call.response.status(HttpStatusCode.Unauthorized)
            call.respondText("Unauthorized.")
        }

        if (receiverID != null && message != null) {
            // send message to webhook

            call.response.status(HttpStatusCode.OK)
            call.respondText("Message sent successfully.")
        } else {
            call.response.status(HttpStatusCode.BadRequest)
            call.respondText("Missing required query parameters.")
        }

    }
}