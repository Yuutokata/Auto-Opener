package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.auth.JwtService
import de.yuuto.autoOpener.auth.Roles
import de.yuuto.autoOpener.auth.ServiceTokens
import de.yuuto.autoOpener.plugins.ErrorResponse
import de.yuuto.autoOpener.plugins.TOKEN_LIMIT
import de.yuuto.autoOpener.users.User
import de.yuuto.autoOpener.users.UserRepository
import de.yuuto.autoOpener.users.UserSecrets
import de.yuuto.autoOpener.users.userIdPattern
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

fun Route.tokenRoutes(
    jwtService: JwtService,
    users: UserRepository,
    serviceTokens: ServiceTokens,
    requireUserSecret: Boolean,
) {
    val logger = LoggerFactory.getLogger("TokenRoutes")

    rateLimit(TOKEN_LIMIT) {
        post("/token") {
            val request = call.receive<TokenRequest>()
            if (request.token.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("A token/id is required"))
                return@post
            }

            val role = request.role ?: Roles.USER
            val subject = when (role) {
                Roles.USER -> {
                    if (!userIdPattern.matches(request.token)) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid User ID format"))
                        return@post
                    }
                    val user = users.find(request.token)
                    if (user == null) {
                        logger.info("Token refused: unknown user {}", request.token)
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("User ID not found in database, this could take some time of you are new to the server."),
                        )
                        return@post
                    }
                    if (!secretAccepted(user, request.secret, requireUserSecret)) {
                        logger.warn("Token refused: wrong or missing secret for user {}", user.id)
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid user secret"))
                        return@post
                    }
                    user.id
                }

                Roles.SERVICE -> serviceTokens.subjectFor(request.token) ?: run {
                    logger.warn("Token refused: invalid service token")
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Service token not found in database"))
                    return@post
                }

                else -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid role. Must be 'user' or 'service'"))
                    return@post
                }
            }

            call.respond(TokenResponse(jwtService.issue(subject, role)))
        }
    }
}

/**
 * While rollout is in progress ([requireUserSecret] false) users without a stored secret, and clients that
 * send none, are still accepted so existing extensions keep working. A secret that is sent must always match.
 */
private fun secretAccepted(user: User, provided: String?, requireUserSecret: Boolean): Boolean {
    val stored = user.secretHash
    return if (provided != null && stored != null) UserSecrets.matches(provided, stored) else !requireUserSecret
}
