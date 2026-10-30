package de.yuuto.autoOpener

import de.yuuto.autoOpener.config.AppConfig
import de.yuuto.autoOpener.config.ConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class ConfigTest {
    @Test
    fun `applies defaults for optional values`() {
        val config = testConfig()
        assertEquals(8080, config.port)
        assertEquals(72.hours, config.jwt.ttl)
        assertEquals(25.seconds, config.webSocket.pongTimeout)
        assertEquals(3, config.webSocket.maxConnectionsPerUser)
        assertEquals(false, config.requireUserSecret)
        assertNull(config.clientIpHeader)
    }

    @Test
    fun `fails fast on missing secrets`() {
        for (key in listOf("JWT_SECRET", "BOT_TOKEN", "MONGO_URI")) {
            val error = assertFailsWith<ConfigurationException> { AppConfig.from((baseEnvironment - key)::get) }
            assertEquals("Missing required environment variable $key", error.message)
        }
    }

    @Test
    fun `rejects weak or malformed service tokens`() {
        assertFailsWith<ConfigurationException> { testConfig(mapOf("BOT_TOKEN" to "short")) }
        assertFailsWith<ConfigurationException> { testConfig(mapOf("BOT_TOKEN" to "${"a".repeat(40)},bad token")) }
    }

    @Test
    fun `rejects non-numeric values without echoing them`() {
        val error = assertFailsWith<ConfigurationException> { testConfig(mapOf("SERVER_PORT" to "eighty")) }
        assertEquals("Environment variable SERVER_PORT has an invalid value", error.message)
    }

    @Test
    fun `accepts several service tokens`() {
        val second = "b".repeat(40)
        assertEquals(2, testConfig(mapOf("BOT_TOKEN" to "$SERVICE_TOKEN, $second")).serviceTokens.size)
    }
}
