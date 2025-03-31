package de.yuuto.autoOpener.dataclass

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class ConnectionMetrics(
    val lastPingSent: AtomicLong = AtomicLong(0),
    val networkLatency: AtomicLong = AtomicLong(0),
    val invalidResponses: AtomicInteger = AtomicInteger(0),
    val sequenceMismatches: AtomicInteger = AtomicInteger(0)
)