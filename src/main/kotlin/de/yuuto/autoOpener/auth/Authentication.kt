package de.yuuto.autoOpener.auth

import de.yuuto.autoOpener.users.UserRepository
import de.yuuto.autoOpener.users.userIdPattern
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond

const val USER_AUTH = "auth-user"
const val SERVICE_AUTH = "auth-service"

fun Application.configureAuthentication(jwtService: JwtService, users: UserRepository, serviceTokens: ServiceTokens) {
    authentication {
        jwtScheme(USER_AUTH, "WebSocket Access", jwtService, "Invalid user token") { credential ->
            val id = credential.subjectFor(Roles.USER)
            if (id != null && userIdPattern.matches(id) && users.find(id) != null) JWTPrincipal(credential.payload) else null
        }
        jwtScheme(SERVICE_AUTH, "Service Access", jwtService, "Invalid service token") { credential ->
            val token = credential.subjectFor(Roles.SERVICE)
            if (token != null && serviceTokens.isValidSubject(token)) JWTPrincipal(credential.payload) else null
        }
    }
}

private fun AuthenticationConfig.jwtScheme(
    name: String,
    realm: String,
    jwtService: JwtService,
    failureMessage: String,
    validate: suspend (JWTCredential) -> JWTPrincipal?,
) {
    jwt(name) {
        this.realm = realm
        verifier(jwtService.verifier)
        authHeader { call -> extractToken(call) }
        validate { credential -> validate(credential) }
        challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to failureMessage)) }
    }
}

private fun JWTCredential.subjectFor(role: String): String? {
    if (payload.getClaim(JwtService.ROLE_CLAIM).asString() != role) return null
    return payload.getClaim(JwtService.TOKEN_CLAIM).asString()?.takeIf { it.isNotEmpty() }
}

/** Browsers cannot set headers on WebSockets, so the token may also travel in `Sec-WebSocket-Protocol`. */
private fun extractToken(call: ApplicationCall): HttpAuthHeader? {
    val authorization = call.request.headers["Authorization"]
    if (authorization != null && authorization.startsWith("Bearer ")) {
        return HttpAuthHeader.Single("Bearer", authorization.removePrefix("Bearer ").trim())
    }
    val protocol = call.request.headers["Sec-WebSocket-Protocol"]
    if (protocol != null && isJwtStructure(protocol)) return HttpAuthHeader.Single("Bearer", protocol)
    return null
}
