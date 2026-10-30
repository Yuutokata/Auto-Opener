package de.yuuto.autoOpener

import de.yuuto.autoOpener.config.AppConfig
import de.yuuto.autoOpener.routes.TokenRequest
import de.yuuto.autoOpener.routes.TokenResponse
import de.yuuto.autoOpener.users.InMemoryUserRepository
import de.yuuto.autoOpener.users.UserRepository
import de.yuuto.autoOpener.websocket.ConnectionRegistry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

const val USER_ID = "123456789012345678"
const val OTHER_USER_ID = "223456789012345678"
val SERVICE_TOKEN = "s".repeat(40)
val USER_SECRET = "ab".repeat(32)

val baseEnvironment = mapOf(
    "BOT_TOKEN" to SERVICE_TOKEN,
    "JWT_SECRET" to "test-secret-test-secret-test-secret",
    "MONGO_URI" to "mongodb://unused",
)

fun testConfig(overrides: Map<String, String> = emptyMap()): AppConfig = (baseEnvironment + overrides).let { env -> AppConfig.from(env::get) }

class TestApp(val builder: ApplicationTestBuilder, val users: UserRepository, val registry: ConnectionRegistry) {
    val client: HttpClient = builder.createClient {
        install(ContentNegotiation) { json() }
        install(WebSockets)
    }

    suspend fun userToken(id: String = USER_ID, secret: String? = null): String =
        client.post("/token") {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(id, "user", secret))
        }.body<TokenResponse>().token

    suspend fun serviceToken(): String =
        client.post("/token") {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(SERVICE_TOKEN, "service"))
        }.body<TokenResponse>().token

    /** Waits until the server registered [count] live connections for [userId]. */
    suspend fun awaitConnections(userId: String, count: Int) {
        withTimeout(5.seconds) {
            while (registry.forUser(userId).size != count) delay(20)
        }
    }
}

/** Runs the real module against an in-memory user store. */
fun runApp(
    config: AppConfig = testConfig(),
    users: UserRepository = InMemoryUserRepository(),
    block: suspend TestApp.() -> Unit,
) = testApplication {
    val registry = ConnectionRegistry(config.webSocket.maxConnectionsPerUser)
    application { module(config, users, registry) }
    TestApp(this, users, registry).block()
}

/** In-memory stand-in for a socket; frames written by the server land in [outgoing]. */
class FakeSession : WebSocketSession {
    override val coroutineContext: CoroutineContext = Job()
    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override val incoming = Channel<Frame>(Channel.UNLIMITED)
    override val outgoing = Channel<Frame>(Channel.UNLIMITED)
    override val extensions: List<WebSocketExtension<*>> = emptyList()

    override suspend fun flush() = Unit

    @Deprecated("Use cancel() instead.", replaceWith = ReplaceWith("cancel()"))
    override fun terminate() = Unit

    fun sent(): List<Frame> = buildList { while (true) add(outgoing.tryReceive().getOrNull() ?: break) }
}
