package de.yuuto.autoOpener.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import de.yuuto.autoOpener.util.Config
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.Date
import java.util.concurrent.TimeUnit


fun Route.generateToken() {
    val secret = Config.getSecret()
    val issuer = Config.getIssuer()
    val audience = Config.getAudience()
    post("/token") {
        val userId = call.receive<Parameters>()["userId"]?: error("Missing userId")

        val token = JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId)
            .withExpiresAt(Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1))) // Expires in 24 hours
            .sign(Algorithm.HMAC256(secret))

        call.respond(hashMapOf("token" to token))
    }
}