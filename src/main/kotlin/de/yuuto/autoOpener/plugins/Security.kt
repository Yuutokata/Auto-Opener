package de.yuuto.autoOpener.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import de.yuuto.autoOpener.util.Config
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.defaultheaders.*
import javax.security.sasl.AuthenticationException

fun Application.configureSecurity() {
    val secret = Config.getSecret()
    val issuer = Config.getIssuer()
    val audience = Config.getAudience()

    authentication {
        jwt("auth-jwt") {
            realm = "WebSocket Service"
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withAudience(audience)
                    .withIssuer(issuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(audience)) {
                    JWTPrincipal(credential.payload)
                } else null
            }
            challenge { _, _ ->
                throw AuthenticationException("Invalid token")
            }
        }
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