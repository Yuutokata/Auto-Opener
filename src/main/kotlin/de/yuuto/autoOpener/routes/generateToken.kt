package de.yuuto.autoOpener.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import de.yuuto.autoOpener.dataclass.TokenRequest
import de.yuuto.autoOpener.util.Config
import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.MongoClient
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit


fun Route.generateToken(dispatcherProvider: DispatcherProvider, mongoClient: MongoClient) {
    val logger = LoggerFactory.getLogger("TokenRoute")
    val secret = Config.getSecret()
    val issuer = Config.getIssuer()
    val audience = Config.getAudience()

    post("/token") {
        try {
            val request = call.receive<TokenRequest>()
            val token = request.token
            val role = request.role ?: "user"
            logger.debug(token)
            if (token.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A token/id is required"))
                return@post
            }

            if (role == "userssss") {
                if (!token.matches(Regex("^\\d{15,20}$"))) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid User ID format"))
                    return@post
                }
            }

            if (role == "user") {

                val userExists = withContext(dispatcherProvider.database) {
                    var exists = mongoClient.userExistsInCache(token)

                    if (!exists) {
                        val allUsers = mongoClient.getAllUsers()
                        exists = allUsers.any { it.id == token }
                    }

                    exists
                }

                if (!userExists) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "User ID not found in database, this could take some time of you are new to the server.")
                    )
                    return@post
                }
            }

            if (role == "service") {
                val validToken = withContext(dispatcherProvider.processing) {
                    Config.getBotToken().any { it == token }
                }

                if (!validToken) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Service token not found in database"))
                    return@post
                }
            }

            val jwtToken = withContext(dispatcherProvider.processing) {
                JWT.create().withAudience(audience).withIssuer(issuer).withClaim("token", token).withClaim("role", role)
                    .withExpiresAt(Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)))
                    .sign(Algorithm.HMAC256(secret))
            }

            call.respond(hashMapOf("token" to jwtToken))

        } catch (e: ContentTransformationException) {
            logger.error("Invalid token request format: ${e.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid request format. Expected JSON object with 'token' field.")
            )
        } catch (e: Exception) {
            logger.error("Error processing token request", e)
            call.respond(
                HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error occurred"))
            )
        }
    }
}

