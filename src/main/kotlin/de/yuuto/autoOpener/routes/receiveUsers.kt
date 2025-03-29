package de.yuuto.autoOpener.routes

import de.yuuto.autoOpener.dataclass.SingleUserRequest
import de.yuuto.autoOpener.dataclass.UserRemoveRequest
import de.yuuto.autoOpener.dataclass.UsersList
import de.yuuto.autoOpener.util.MongoClient
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

fun Route.receiveUsers() {
    val logger = LoggerFactory.getLogger("ReceiveUsersRoute")
    
    authenticate("auth-service") {
        post("/users") {
            try {
                val usersList = call.receive<UsersList>()
                
                // Check for empty user list
                if (usersList.users.isEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest, 
                        mapOf("error" to "Empty user list provided")
                    )
                    return@post
                }
                
                // Check for duplicate IDs
                val duplicateIds = usersList.users.groupBy { it.id }
                    .filter { it.value.size > 1 }
                    .keys
                
                if (duplicateIds.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest, 
                        mapOf(
                            "error" to "Duplicate user IDs found",
                            "duplicateIds" to duplicateIds
                        )
                    )
                    return@post
                }

                logger.debug("Processing synchronization request with ${usersList.users.size} users")
                val mongoClient = MongoClient.getInstance()
                
                var syncResult: de.yuuto.autoOpener.dataclass.SyncResult
                val execTime = measureTimeMillis {
                    syncResult = mongoClient.synchronizeUsers(usersList.users)
                }
                logger.debug("User synchronization completed in ${execTime}ms")

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
                logger.error("Invalid request format: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest, 
                    mapOf("error" to "Invalid request format. Please check your JSON structure.")
                )
            } catch (e: Exception) {
                logger.error("Error synchronizing users", e)
                call.respond(
                    HttpStatusCode.InternalServerError, 
                    mapOf("error" to (e.message ?: "Unknown error occurred"))
                )
            }
        }

        post("/users/add") {
            try {
                val request = call.receive<SingleUserRequest>()
                val user = request.user
                
                if (user.id.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "User ID cannot be empty")
                    )
                    return@post
                }

                val mongoClient = MongoClient.getInstance()
                
                // Check if user already exists (for better messaging)
                val userExists = mongoClient.userExists(user.id)
                val success = mongoClient.addUser(user)

                if (success) {
                    val statusMessage = if (userExists) "User updated successfully" else "User added successfully"
                    call.respond(
                        HttpStatusCode.OK, mapOf(
                            "message" to statusMessage,
                            "userId" to user.id,
                            "operation" to if (userExists) "updated" else "created"
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError, mapOf(
                            "error" to "Failed to add user"
                        )
                    )
                }
            } catch (e: ContentTransformationException) {
                call.respond(
                    HttpStatusCode.BadRequest, 
                    mapOf("error" to "Invalid user data format. Please check your JSON structure.")
                )
            } catch (e: Exception) {
                logger.error("Error adding user", e)
                call.respond(
                    HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error occurred"))
                )
            }
        }

        post("/users/remove") {
            try {
                val request = call.receive<UserRemoveRequest>()
                val userId = request.userId
                
                if (userId.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "User ID cannot be empty")
                    )
                    return@post
                }

                val mongoClient = MongoClient.getInstance()
                val (success, message) = mongoClient.removeUser(userId)

                if (success) {
                    call.respond(
                        HttpStatusCode.OK, mapOf(
                            "message" to "User removed successfully", 
                            "userId" to userId
                        )
                    )
                } else {
                    if (message == "User does not exist") {
                        call.respond(
                            HttpStatusCode.NotFound, mapOf(
                                "error" to "User with ID $userId not found"
                            )
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError, mapOf(
                                "error" to "Failed to remove user: $message"
                            )
                        )
                    }
                }
            } catch (e: ContentTransformationException) {
                call.respond(
                    HttpStatusCode.BadRequest, 
                    mapOf("error" to "Invalid request format. Please check your JSON structure.")
                )
            } catch (e: Exception) {
                logger.error("Error removing user", e)
                call.respond(
                    HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error occurred"))
                )
            }
        }
        
        post("/users/refresh-cache") {
            try {
                val mongoClient = MongoClient.getInstance()
                val users = mongoClient.refreshCache()

                call.respond(
                    HttpStatusCode.OK, mapOf(
                        "message" to "Cache refreshed successfully", 
                        "userCount" to users.size
                    )
                )
            } catch (e: Exception) {
                logger.error("Error refreshing cache", e)
                call.respond(
                    HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error occurred"))
                )
            }
        }

        get("/health/mongodb") {
            try {
                val isConnected = MongoClient.checkConnection()
                if (isConnected) {
                    call.respond(
                        HttpStatusCode.OK, 
                        mapOf("status" to "Connected", "message" to "MongoDB connection is healthy")
                    )
                } else {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable, 
                        mapOf("status" to "Disconnected", "message" to "MongoDB connection failed")
                    )
                }
            } catch (e: Exception) {
                logger.error("MongoDB health check failed", e)
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("status" to "Error", "message" to "MongoDB health check failed: ${e.message}")
                )
            }
        }
    }
}

