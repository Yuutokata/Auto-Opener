package de.yuuto.autoOpener.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import de.yuuto.autoOpener.dependencyProvider
import de.yuuto.autoOpener.util.Config
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.response.*

fun Application.configureSecurity() {
    val secret = Config.getSecret()
    val issuer = Config.getIssuer()
    val audience = Config.getAudience()

    authentication {
        jwt("auth-service") {
            realm = "Service Access"
            verifier(
                JWT.require(Algorithm.HMAC256(secret)).withAudience(audience).withIssuer(issuer).build()
            )
            validate { credential ->
                val tokenClaim = credential.payload.getClaim("token").asString()
                if (credential.payload.audience.contains(audience) && credential.payload.getClaim("role")
                        .asString() == "service" && !tokenClaim.isNullOrEmpty() && tokenClaim.matches(Regex("^[a-zA-Z0-9]{32,64}$"))
                ) {
                    JWTPrincipal(credential.payload)
                } else false
            }
            challenge { _, _ ->
                call.respond("Authentication failed: Invalid service token")
                return@challenge
            }
        }

        jwt("auth-user") {
            realm = "WebSocket Access"
            verifier(
                JWT.require(Algorithm.HMAC256(secret)).withAudience(audience).withIssuer(issuer).build()
            )
            validate { credential ->
                val tokenClaim = credential.payload.getClaim("token").asString()
                if (credential.payload.audience.contains(audience) && credential.payload.getClaim("role")
                        .asString() == "user" && !tokenClaim.isNullOrEmpty() && tokenClaim.matches(Regex("^\\d{15,20}$"))
                ) {
                    if (isValidUserToken(tokenClaim)) {
                        JWTPrincipal(credential.payload)
                    } else {
                        false
                    }
                } else false
            }
            authHeader { call ->
                call.request.headers["Sec-WebSocket-Protocol"]?.let { token ->
                    if (isValidJwtStructure(token)) {
                        HttpAuthHeader.Single("Bearer", token)
                    } else {
                        null
                    }
                }
            }
            challenge { _, _ ->
                call.respond("Authentication failed: Invalid user token")
            }
        }
    }
}

private suspend fun isValidUserToken(token: String): Boolean {
    val userExists =
        dependencyProvider.mongoClient.userExistsInCache(token) || dependencyProvider.mongoClient.userExists(token)
    val serviceToken = Config.getBotToken().any { it == token }
    return userExists || serviceToken
}

internal fun isValidJwtStructure(token: String): Boolean {
    return try {
        JWT.decode(token)
        true
    } catch (e: Exception) {
        false
    }
}

fun Application.configureSecurityHeaders() {
    install(DefaultHeaders) {
        header("Content-Security-Policy", "default-src 'self'")
        header("X-Frame-Options", "DENY")
        header("X-Content-Type-Options", "nosniff")
        header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        header("Referrer-Policy", "no-referrer-when-downgrade")
    }
}
