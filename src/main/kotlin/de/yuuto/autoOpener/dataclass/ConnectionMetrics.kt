package de.yuuto.autoOpener.dataclass

data class ConnectionMetrics(
    val connectionStartTime: Long = System.currentTimeMillis(),
    var lastActivityTimestamp: Long = System.currentTimeMillis(),
    var messagesReceived: Long = 0,
    var messagesSent: Long = 0
) {
    fun updateLastActivity() {
        lastActivityTimestamp = System.currentTimeMillis()
    }

    // Potentially add other methods here if needed, e.g.:
    // fun incrementMessagesReceived() { messagesReceived++ }
    // fun incrementMessagesSent() { messagesSent++ }
}
