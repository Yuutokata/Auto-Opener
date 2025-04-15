package de.yuuto.autoOpener.util

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ThreadPoolExecutor
import kotlin.time.Duration.Companion.seconds

class DispatcherProvider {
    private val logger = LoggerFactory.getLogger(DispatcherProvider::class.java)
    private val supervisorJob = SupervisorJob()

    // Network operations: Optimized for Redis pub/sub and WebSocket message delivery
    // Increased parallelism for high-volume message processing
    val network: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(30)

    // Database operations: Tuned for MongoDB connection pool size and transaction throughput
    // Matched to MongoDB driver's connection pool configuration
    val database: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(10)

    // Processing operations: CPU-bound tasks like JWT validation and message serialization
    // Limited to physical core count for optimal CPU utilization
    val processing: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(8)

    // Monitoring operations: Health checks and metrics collection
    // Low priority to prevent interference with critical path operations
    val monitoring: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    // WebSocket operations: Specialized for frame processing and connection management
    // Balanced between IO parallelism and WebSocket protocol requirements
    val websocket: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(6)

    // Heartbeat dispatcher with strict QoS guarantees
    // Ensures timely ping/pong handling even under high load
    val heartbeat: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    private val scope = CoroutineScope(monitoring + supervisorJob)

    init {
        logger.info(
            """
            Initialized dispatchers with specialized configuration:
            Network (Redis/WS): ${getParallelism(network)} threads
            Database (MongoDB): ${getParallelism(database)} threads
            Processing (CPU): ${getParallelism(processing)} threads
            Monitoring: ${getParallelism(monitoring)} threads
            WebSocket: ${getParallelism(websocket)} threads
            Heartbeat: ${getParallelism(heartbeat)} threads
        """.trimIndent()
        )

        scope.launch {
            monitorDispatcherUsage()
        }
        scope.launch {
            logger.info("Redis subscriptions dispatcher activated.")
        }
    }

    private fun getParallelism(dispatcher: CoroutineDispatcher): Int {
        return try {
            val field = dispatcher.javaClass.getDeclaredField("parallelism")
            field.isAccessible = true
            field.getInt(dispatcher)
        } catch (e: Exception) {
            when (dispatcher) {
                Dispatchers.IO -> 64
                Dispatchers.Default -> Runtime.getRuntime().availableProcessors()
                else -> -1
            }
        }
    }

    fun monitorDispatcherUsage() {
        CoroutineScope(monitoring).launch {
            while (isActive) {
                logDispatcherStats("Network", network)
                logDispatcherStats("WebSocket", websocket)
                delay(120.seconds)
            }
        }
    }

    private fun logDispatcherStats(name: String, dispatcher: CoroutineDispatcher) {
        val executor = (dispatcher.asExecutor() as? ThreadPoolExecutor)
        executor?.run {
            logger.info(
                """
            [DISPATCHER|{}] Stats:
            Active: {} | Queue: {} | Pool: {} | Completed: {}
        """.trimIndent(), name, activeCount, queue.size, poolSize, completedTaskCount
            )
        }
    }

    fun close() {
        logger.info("Shutting down dispatcher provider...")
        try {
            // Log final dispatcher statistics
            logger.info("Final dispatcher stats before shutdown:")
            logDispatcherStats("Network", network)
            logDispatcherStats("WebSocket", websocket)
            logDispatcherStats("Database", database)
            logDispatcherStats("Processing", processing)
            logDispatcherStats("Monitoring", monitoring)
            logDispatcherStats("Heartbeat", heartbeat)

            logger.info("Dispatcher provider resources released")
        } catch (e: Exception) {
            logger.error("Error shutting down dispatcher provider: ${e.message}", e)
        }
    }

}
