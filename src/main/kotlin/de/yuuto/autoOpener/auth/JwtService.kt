package de.yuuto.autoOpener.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import de.yuuto.autoOpener.config.JwtSettings
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

object Roles {
    const val USER = "user"
    const val SERVICE = "service"
}

/** Issues and verifies the HMAC-signed tokens used by the extension (role `user`) and the bot (role `service`). */
class JwtService(private val settings: JwtSettings, private val clock: () -> Long = System::currentTimeMillis) {
    private val algorithm = Algorithm.HMAC256(settings.secret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(settings.issuer)
        .withAudience(settings.audience)
        .build()

    fun issue(subject: String, role: String): String = JWT.create()
        .withIssuer(settings.issuer)
        .withAudience(settings.audience)
        .withClaim(TOKEN_CLAIM, subject)
        .withClaim(ROLE_CLAIM, role)
        .withExpiresAt(Date(clock() + settings.ttl.inWholeMilliseconds))
        .sign(algorithm)

    companion object {
        const val TOKEN_CLAIM = "token"
        const val ROLE_CLAIM = "role"
    }
}

/** Cheap structural check, used to avoid echoing arbitrary `Sec-WebSocket-Protocol` values. */
fun isJwtStructure(value: String): Boolean = value.count { it == '.' } == 2 && value.all { it.isLetterOrDigit() || it in "-_." }
