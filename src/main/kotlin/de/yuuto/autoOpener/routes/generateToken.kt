package de.yuuto.autoOpener.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import de.yuuto.autoOpener.dataclass.TokenRequest
import de.yuuto.autoOpener.util.Config
import de.yuuto.autoOpener.util.DispatcherProvider
import de.yuuto.autoOpener.util.MongoClient
import io.ktor.http.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.*
import java.util.concurrent.TimeUnit


fun Route.generateToken(dispatcherProvider: DispatcherProvider, mongoClient: MongoClient) {
    val logger = LoggerFactory.getLogger("TokenRoute")
    val secret = Config.getSecret()
    val issuer = Config.getIssuer()
    val audience = Config.getAudience()
    rateLimit(RateLimitName("user")) {
        post("/token") {
            val callId = UUID.randomUUID().toString()
            MDC.put("call_id", callId)
            MDC.put("route", "/token")
            MDC.put("http_method", "POST")
            logger.info("Received token generation request")

            try {
                val request = call.receive<TokenRequest>()
                val tokenInput = request.token
                val role = request.role ?: "user"
                MDC.put("requested_role", role)
                MDC.put("provided_token_identifier", tokenInput)

                if (tokenInput.isBlank()) {
                    MDC.put("validation_error", "Token/ID is blank")
                    logger.warn("Token generation failed: Blank token/ID provided")
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A token/id is required"))
                    MDC.clear()
                    return@post
                }

                if (role != "user" && role != "service") {
                    MDC.put("validation_error", "Invalid role: $role")
                    logger.warn("Token generation failed: Invalid role provided")
                    call.respond(
                        HttpStatusCode.BadRequest, mapOf("error" to "Invalid role. Must be 'user' or 'service'")
                    )
                    MDC.clear()
                    return@post
                }

                if (role == "user") {
                    MDC.put("user_id", tokenInput)
                    if (!tokenInput.matches(Regex("^\\d{15,20}$"))) {
                        MDC.put("validation_error", "Invalid User ID format")
                        logger.warn("Token generation failed: Invalid User ID format")
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid User ID format"))
                        MDC.clear()
                        return@post
                    }
                    val userExists = mongoClient.userExists(tokenInput)

                    if (!userExists) {
                        MDC.put("auth_status", "failure")
                        MDC.put("reason", "User ID not found")
                        logger.warn("Token generation failed: User ID not found")
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found in database, this could take some time of you are new to the server.")
                        )
                        MDC.clear()
                        return@post
                    }
                }

                if (role == "service") {
                    MDC.put("service_token_provided", "true")
                    val validToken = withContext(dispatcherProvider.processing) {
                        Config.getBotToken().any { it == tokenInput }
                    }

                    if (!validToken) {
                        MDC.put("auth_status", "failure")
                        MDC.put("reason", "Invalid service token")
                        logger.warn("Token generation failed: Invalid service token")
                        call.respond(
                            HttpStatusCode.Unauthorized, mapOf("error" to "Service token not found in database")
                        )
                        MDC.clear()
                        return@post
                    }
                }

                MDC.put("auth_status", "success")
                val jwtToken = withContext(dispatcherProvider.processing) {
                    JWT.create().withAudience(audience).withIssuer(issuer).withClaim("token", tokenInput)
                        .withClaim("role", role)
                        .withExpiresAt(Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3)))
                        .sign(Algorithm.HMAC256(secret))
                }

                MDC.put("jwt_generated", "true")
                logger.info("JWT token generated successfully")
                call.respond(hashMapOf("token" to jwtToken))

            } catch (e: ContentTransformationException) {
                MDC.put("error_type", "ContentTransformationException")
                logger.error("Invalid token request format", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid request format. Expected JSON object with 'token' and optional 'role' field.")
                )
            } catch (e: Exception) {
                MDC.put("error_type", e::class.simpleName ?: "UnknownException")
                logger.error("Error processing token request", e)
                call.respond(
                    HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error occurred"))
                )
            } finally {
                MDC.clear()
            }
        }
    }
}

