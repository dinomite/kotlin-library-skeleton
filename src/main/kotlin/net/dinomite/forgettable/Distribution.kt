package net.dinomite.forgettable

import redis.clients.jedis.JedisPool
import java.time.Instant

class Distribution(jedisPool: JedisPool, val name: String, val expireSigma: Int = 2) : Jedised(jedisPool) {
    companion object {
        val MAXIMUM = "+inf"
        val MINIMUM = "-inf"
    }

    val data = mutableMapOf<String, Value>()

    // Total count
    private val zKey = "${name}_Z"
    private var z: Int = 0
    private fun fetchZ() { z = jedis { it.get(zKey) }.toInt() }
    private fun writeZ() = jedis { it.set(zKey, z.toString()) }

    // Last decay time
    private val tKey = "${name}_T"
    private var t: Instant = Instant.now()
    private fun fetchT() { t = Instant.ofEpochSecond(jedis { it.get(tKey) }.toLong()) }
    private fun writeT() = jedis { it.set(tKey, t.epochSecond.toString()) }

    private val rateKey = "${name}_rate"
    private var rate: Double = 0.5
    private fun fetchRate() { rate = jedis { it.get(rateKey) }.toDouble() }
    private fun writeRate() = jedis { it.set(rateKey, rate.toString()) }

    private var prune: Boolean = false
    private var decayed: Boolean = false

    // distribution#Fill
    init {
        writeRate()
        load()
        normalize()
    }

    fun getMostProbable(num: Int): Map<String, Double> {
        fetchZ()
        fetchT()
        load()
    }

    // distribution#GetField
    fun fetchBin(bin: String): Value? {
        data[bin] = Value(jedis { it.zscore(name, bin) }.toInt())

        calcProbabilities()

        return data[bin]
    }

    // distribution#addMultiBulkCounts
    fun load() {
        jedis { it.zrangeWithScores(name, 0, -1) }.forEach {
            data.put(it.element, Value(it.score.toInt()))
        }
    }

    /**
     * Write the distribution data to Redis
     */
    // redis_utils#UpdateRedis (really redis_utils#UpdateDistribution)
    fun update() {
        jedis { it.watch(zKey) }

        var maxCount = 0
        pipeline { p ->
            if (decayed) {
                if (z == 0) {
                    p.discard()
                    // TODO return DistEmpty
                }

                data.forEach { bin, value ->
                    if (value.count == 0) {
                        p.zrem(name, bin)
                    } else {
                        p.zadd(name, value.count.toDouble(), bin)
                        if (value.count > maxCount) {
                            maxCount = value.count
                        }
                    }
                }

                p.set(zKey, z.toString())
                p.set(tKey, t.toString())
            } else {
                data.forEach { _, value ->
                    if (value.count != 0 && value.count > maxCount) {
                        maxCount = value.count
                    }
                }
            }

            val eta = Math.sqrt(maxCount / rate)
            val expiryTime = ((expireSigma + eta) * eta).toInt()
            p.expire(name, expiryTime)
            p.expire(zKey, expiryTime)
            p.expire(tKey, expiryTime)
        }

        jedis { it.unwatch() }
    }

    // distribution#Normalize
    fun normalize() {
        z = data.map { it.value.count }.fold(0) { total, count -> total + count }
        writeZ()
        calcProbabilities()
    }

    // distribution#calcProbabilities
    fun calcProbabilities() {
        fetchZ()
        fetchT()

        data.forEach { _, value ->
            if (z == 0) {
                value.p = 0.0
            } else {
                value.p = value.count.toDouble() / z
            }
        }
    }

    // Total count
//    private val zKey = "${name}_Z"
    // Last decay time
//    private val tKey = "${name}_T"
    fun decay() {
        val startingZ = z
        val now = Instant.now()

        data.forEach { key, value ->
            var l = decayTime(value.count, t, rate, now)
            if (l >= data[key]!!.count) {
                if (prune) {
                    l = data[key]!!.count
                } else {
                    l = data[key]!!.count - 1
                }

                data[key]!!.count -= l
                z -= l
            }
        }

        if (decayed && startingZ != z) {
            decayed = true
        }

        t = Instant.now()
        writeT()
        calcProbabilities()
    }
}

data class Value(var count: Int, var p: Double? = null)
