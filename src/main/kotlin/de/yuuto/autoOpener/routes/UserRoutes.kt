package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.auth.SERVICE_AUTH
import de.yuuto.autoOpener.plugins.ErrorResponse
import de.yuuto.autoOpener.plugins.SERVICE_LIMIT
import de.yuuto.autoOpener.users.UserRepository
import de.yuuto.autoOpener.users.UserSecrets
import de.yuuto.autoOpener.users.userIdPattern
import de.yuuto.autoOpener.users.userSecretPattern
import de.yuuto.autoOpener.websocket.ConnectionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.websocket.CloseReason
import org.slf4j.LoggerFactory
import kotlin.time.measureTimedValue

fun Route.userRoutes(users: UserRepository, connections: ConnectionManager) {
    val logger = LoggerFactory.getLogger("UserRoutes")

    authenticate(SERVICE_AUTH) {
        rateLimit(SERVICE_LIMIT) {
            post("/users") {
                val list = call.receive<UsersList>().users
                if (list.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Empty user list provided"))
                    return@post
                }
                val invalid = list.firstOrNull { !userIdPattern.matches(it.id) }
                if (invalid != null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID format"))
                    return@post
                }
                val duplicates = list.groupBy { it.id }.filterValues { it.size > 1 }.keys
                if (duplicates.isNotEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, DuplicateIdsResponse("Duplicate user IDs found", duplicates))
                    return@post
                }

                val (outcome, elapsed) = measureTimedValue { users.synchronize(list.map { it.id to it.name }) }
                outcome.removedIds.forEach {
                    connections.disconnectUser(it, CloseReason(CloseReason.Codes.VIOLATED_POLICY, "User removed"))
                }
                logger.info("Synchronized users: added={} removed={} unchanged={}", outcome.added, outcome.removed, outcome.unchanged)
                call.respond(
                    SyncResponse("Users synchronized successfully", outcome.added, outcome.removed, outcome.unchanged, elapsed.inWholeMilliseconds)
                )
            }

            post("/users/add") {
                val user = call.receive<SingleUserRequest>().user
                if (user.id.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID cannot be empty"))
                    return@post
                }
                if (!userIdPattern.matches(user.id)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID format"))
                    return@post
                }
                if (user.secret != null && !userSecretPattern.matches(user.secret)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid secret format"))
                    return@post
                }

                val created = users.upsert(user.id, user.name, user.secret?.let(UserSecrets::hash))
                val operation = if (created) "created" else "updated"
                logger.info("User {} {}{}", user.id, operation, if (user.secret != null) " (secret set)" else "")
                call.respond(
                    UserAddedResponse(
                        message = if (created) "User added successfully" else "User updated successfully",
                        userId = user.id,
                        operation = operation,
                    )
                )
            }

            post("/users/remove") {
                val userId = call.receive<UserRemoveRequest>().userId
                if (userId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID cannot be empty"))
                    return@post
                }
                if (!users.delete(userId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User with ID $userId not found"))
                    return@post
                }
                connections.disconnectUser(userId, CloseReason(CloseReason.Codes.VIOLATED_POLICY, "User removed"))
                logger.info("User {} removed", userId)
                call.respond(UserRemovedResponse("User removed successfully", userId))
            }
        }
    }
}
