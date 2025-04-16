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
import org.slf4j.LoggerFactory

fun Application.configureSecurity() {
    val secret = Config.getSecret()
    val issuer = Config.getIssuer()
    val audience = Config.getAudience()
    val logger = LoggerFactory.getLogger(org.slf4j.Logger::class.java)

    authentication {
        jwt("auth-service") {
            realm = "Service Access"
            verifier(
                JWT.require(Algorithm.HMAC256(secret)).withAudience(audience).withIssuer(issuer).build()
            )
            validate { credential ->
                val tokenClaim = credential.payload.getClaim("token").asString()
                val role = credential.payload.getClaim("role").asString()

                if (credential.payload.audience.contains(audience) && role == "service" && !tokenClaim.isNullOrEmpty() && tokenClaim.matches(
                        Regex("^[a-zA-Z0-9]{32,128}$")
                    )
                ) {
                    if (isValidUserToken(role = role, token = tokenClaim)) {
                        JWTPrincipal(credential.payload)
                    } else {
                        logger.warn("Service token validation failed")
                        null
                    }
                } else {
                    logger.warn("JWT validation failed - audience: ${credential.payload.audience}, role: $role")
                    null
                }
            }
            challenge { _, _ ->
                call.respond("Authentication failed: Invalid service token")
                return@challenge
            }
            authHeader { call -> extractJwtFromHeaders(call) }
        }

        jwt("auth-user") {
            realm = "WebSocket Access"
            verifier(
                JWT.require(Algorithm.HMAC256(secret)).withAudience(audience).withIssuer(issuer).build()
            )
            validate { credential ->
                val tokenClaim = credential.payload.getClaim("token").asString()
                val role = credential.payload.getClaim("role").asString()
                if (credential.payload.audience.contains(audience) && role == "user" && !tokenClaim.isNullOrEmpty() && tokenClaim.matches(
                        Regex("^\\d{15,20}$")
                    )
                ) {
                    if (isValidUserToken(tokenClaim, role)) {
                        JWTPrincipal(credential.payload)
                    } else {
                        false
                    }
                } else false
            }
            authHeader { call -> extractJwtFromHeaders(call) }
            challenge { _, _ ->
                call.respond("Authentication failed: Invalid user token")
            }
        }
    }
}

private fun extractJwtFromHeaders(call: ApplicationCall): HttpAuthHeader? {
    val authHeader = call.request.headers["Authorization"]
    if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ")) {
        val token = authHeader.removePrefix("Bearer ").trim()
        return HttpAuthHeader.Single("Bearer", token)
    }

    val wsProtocol = call.request.headers["Sec-WebSocket-Protocol"]
    if (!wsProtocol.isNullOrBlank() && isValidJwtStructure(wsProtocol)) {
        return HttpAuthHeader.Single("Bearer", wsProtocol)
    }

    return null
}

private suspend fun isValidUserToken(token: String, role: String): Boolean {
    if (role == "user") {
        val userExists =
            dependencyProvider.mongoClient.userExistsInCache(token) || dependencyProvider.mongoClient.userExists(token)
        return userExists
    }
    if (role == "service") {
        val serviceToken = Config.getBotToken().any { it == token }
        return serviceToken
    }
    return false
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
