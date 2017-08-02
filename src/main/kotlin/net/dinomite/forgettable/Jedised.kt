package net.dinomite.forgettable

import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.Pipeline

open class Jedised(val jedisPool: JedisPool) {
    protected inline fun <T> jedis(body: (jedis: Jedis) -> T): T {
        return jedisPool.resource.use { body(it) }
    }

    protected inline fun <T> pipeline(body: (pipeline: Pipeline) -> T): T {
        jedisPool.resource.use {
            it.pipelined().use {
                val ret = body(it)
                it.sync()
                return ret
            }
        }
    }
}