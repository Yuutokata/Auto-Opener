package de.yuuto.autoOpener.plugins

// import com.example.websocket.WebSocketManager
import io.ktor.server.application.*
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config

fun Application.configureRedis() {
    val redisConfig = Config()
    redisConfig.useSingleServer().address = "redis://${de.yuuto.autoOpener.util.Config.getHost()}:${de.yuuto.autoOpener.util.Config.getPort()}"
    val redissonClient: RedissonClient = Redisson.create(redisConfig)

    // WebSocketManager.initialize(redissonClient)
}