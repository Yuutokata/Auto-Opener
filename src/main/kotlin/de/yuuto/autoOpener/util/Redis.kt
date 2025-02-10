package de.yuuto.autoOpener.util

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.codec.Kryo5Codec
import com.esotericsoftware.kryo.Kryo
import org.redisson.codec.JsonJacksonCodec
import org.redisson.config.Config


object Redis {
    private val redisConfig = Config()
    init {
        redisConfig.useSingleServer().address = "redis://${de.yuuto.autoOpener.util.Config.getRedisHost()}:${de.yuuto.autoOpener.util.Config.getRedisPort()}"

        redisConfig.setCodec(JsonJacksonCodec()) // Set the codec
        val redisson: RedissonClient = Redisson.create(redisConfig)

    }
    val redissonClient: RedissonClient by lazy { Redisson.create(redisConfig) }
}