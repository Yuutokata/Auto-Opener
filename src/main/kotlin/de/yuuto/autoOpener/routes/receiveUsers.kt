package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.dataclass.SingleUserRequest
import de.yuuto.autoOpener.dataclass.SyncResult
import de.yuuto.autoOpener.dataclass.UserRemoveRequest
import de.yuuto.autoOpener.dataclass.UsersList
import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.MongoClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID
import kotlin.system.measureTimeMillis

fun Route.receiveUsers(dispatcherProvider: DispatcherProvider, mongoClient: MongoClient) {
    val logger = LoggerFactory.getLogger("ReceiveUsersRoute")
    authenticate("auth-service") {
        post("/users") {
            val callId = UUID.randomUUID().toString()
            MDC.put("call_id", callId)
            MDC.put("route", "/users")
            MDC.put("http_method", "POST")
            logger.info("Received bulk user synchronization request")

            try {
                val usersList = withContext(dispatcherProvider.processing) {
                    call.receive<UsersList>()
                }
                val receivedUserCount = usersList.users.size
                MDC.put("received_user_count", receivedUserCount.toString())

                if (usersList.users.isEmpty()) {
                    MDC.put("validation_error", "Empty user list")
                    logger.warn("User sync failed: Empty user list provided")
                    call.respond(
                        HttpStatusCode.BadRequest, mapOf("error" to "Empty user list provided")
                    )
                    MDC.clear()
                    return@post
                }

                val duplicateIds = withContext(dispatcherProvider.processing) {
                    usersList.users.groupBy { it.id }.filter { it.value.size > 1 }.keys
                }

                if (duplicateIds.isNotEmpty()) {
                    MDC.put("validation_error", "Duplicate user IDs")
                    MDC.put("duplicate_ids", duplicateIds.joinToString(","))
                    logger.warn("User sync failed: Duplicate user IDs found")
                    call.respond(
                        HttpStatusCode.BadRequest, mapOf(
                            "error" to "Duplicate user IDs found", "duplicateIds" to duplicateIds
                        )
                    )
                    MDC.clear()
                    return@post
                }

                logger.debug("Processing synchronization request...")

                var syncResult: SyncResult
                val execTime = measureTimeMillis {
                    syncResult = mongoClient.synchronizeUsers(usersList.users)
                }
                MDC.put("duration_ms", execTime.toString())
                MDC.put("added_count", syncResult.added.toString())
                MDC.put("removed_count", syncResult.removed.toString())
                MDC.put("unchanged_count", syncResult.unchanged.toString())
                logger.info("User synchronization completed")

                call.respond(
                    HttpStatusCode.OK, mapOf(
                        "message" to "Users synchronized successfully",
                        "added" to syncResult.added,
                        "removed" to syncResult.removed,
                        "unchanged" to syncResult.unchanged,
                        "processingTimeMs" to execTime
                    )
                )
            } catch (e: ContentTransformationException) {
                MDC.put("error_type", "ContentTransformationException")
                logger.error("Invalid user sync request format", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid request format. Please check your JSON structure.")
                )
            } catch (e: Exception) {
                MDC.put("error_type", e::class.simpleName ?: "UnknownException")
                logger.error("Error synchronizing users", e)
                call.respond(
                    HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error occurred"))
                )
            } finally {
                MDC.clear()
            }
        }

        post("/users/add") {
            val callId = UUID.randomUUID().toString()
            MDC.put("call_id", callId)
            MDC.put("route", "/users/add")
            MDC.put("http_method", "POST")
            logger.info("Received add/update user request")

            try {
                val request = withContext(dispatcherProvider.processing) {
                    call.receive<SingleUserRequest>()
                }
                val user = request.user
                MDC.put("user_id", user.id)

                if (user.id.isBlank()) {
                    MDC.put("validation_error", "User ID is blank")
                    logger.warn("Add/Update user failed: Blank user ID")
                    call.respond(
                        HttpStatusCode.BadRequest, mapOf("error" to "User ID cannot be empty")
                    )
                    MDC.clear()
                    return@post
                }

                val userExists = mongoClient.userExists(user.id)
                MDC.put("user_existed", userExists.toString())

                val success = mongoClient.addUser(user)
                MDC.put("operation_status", if (success) "success" else "failure")

                if (success) {
                    val statusMessage = if (userExists) "User updated successfully" else "User added successfully"
                    val operation = if (userExists) "updated" else "created"
                    MDC.put("operation_result", operation)
                    logger.info("User add/update successful")
                    call.respond(
                        HttpStatusCode.OK, mapOf(
                            "message" to statusMessage,
                            "userId" to user.id,
                            "operation" to operation
                        )
                    )
                } else {
                    logger.error("Add/Update user failed during database operation")
                    call.respond(
                        HttpStatusCode.InternalServerError, mapOf(
                            "error" to "Failed to add/update user"
                        )
                    )
                }
            } catch (e: ContentTransformationException) {
                MDC.put("error_type", "ContentTransformationException")
                logger.error("Invalid add/update user request format", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid user data format. Please check your JSON structure.")
                )
            } catch (e: Exception) {
                MDC.put("error_type", e::class.simpleName ?: "UnknownException")
                logger.error("Error adding/updating user", e)
                call.respond(
                    HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error occurred"))
                )
            } finally {
                MDC.clear()
            }
        }

        post("/users/remove") {
            val callId = UUID.randomUUID().toString()
            MDC.put("call_id", callId)
            MDC.put("route", "/users/remove")
            MDC.put("http_method", "POST")
            logger.info("Received remove user request")

            try {
                val request = withContext(dispatcherProvider.processing) {
                    call.receive<UserRemoveRequest>()
                }
                val userId = request.userId
                MDC.put("user_id", userId)

                if (userId.isBlank()) {
                    MDC.put("validation_error", "User ID is blank")
                    logger.warn("Remove user failed: Blank user ID")
                    call.respond(
                        HttpStatusCode.BadRequest, mapOf("error" to "User ID cannot be empty")
                    )
                    MDC.clear()
                    return@post
                }

                val (success, message) = mongoClient.removeUser(userId)
                MDC.put("operation_status", if (success) "success" else "failure")
                MDC.put("operation_message", message)

                if (success) {
                    logger.info("User removed successfully")
                    call.respond(
                        HttpStatusCode.OK, mapOf(
                            "message" to "User removed successfully", "userId" to userId
                        )
                    )
                } else {
                    if (message == "User does not exist") {
                        logger.warn("Remove user failed: User not found")
                        call.respond(
                            HttpStatusCode.NotFound, mapOf(
                                "error" to "User with ID $userId not found"
                            )
                        )
                    } else {
                        logger.error("Remove user failed: $message")
                        call.respond(
                            HttpStatusCode.InternalServerError, mapOf(
                                "error" to "Failed to remove user: $message"
                            )
                        )
                    }
                }
            } catch (e: ContentTransformationException) {
                MDC.put("error_type", "ContentTransformationException")
                logger.error("Invalid remove user request format", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid request format. Please check your JSON structure.")
                )
            } catch (e: Exception) {
                MDC.put("error_type", e::class.simpleName ?: "UnknownException")
                logger.error("Error removing user", e)
                call.respond(
                    HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error occurred"))
                )
            } finally {
                MDC.clear()
            }
        }

    }
}

