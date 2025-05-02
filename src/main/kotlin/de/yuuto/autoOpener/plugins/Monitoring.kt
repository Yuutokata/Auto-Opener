package de.yuuto.autoOpener.plugins

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

// Global registry that can be accessed throughout the application
val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
private val logger = LoggerFactory.getLogger("Monitoring")

fun Application.configureMonitoring() {
    // Configure call logging
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }

    // Configure Micrometer with Prometheus registry
    install(MicrometerMetrics) {
        registry = appMicrometerRegistry
        // Add JVM metrics binders
        meterBinders = listOf(
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            ProcessorMetrics(),
            JvmThreadMetrics()
        )
    }
}

// Function to start a separate metrics server
fun startMetricsServer(host: String, port: Int) {
    logger.info("Starting metrics server on $host:$port")
    embeddedServer(Netty, port = port, host = host) {
        routing {
            get("/metrics") {
                call.respond(appMicrometerRegistry.scrape())
            }
        }
    }.start(wait = false)
    logger.info("Metrics server started on $host:$port")
}
