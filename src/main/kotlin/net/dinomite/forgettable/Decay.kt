package net.dinomite.forgettable

import java.time.Instant
import java.util.*

val MAX_ITER = 1000
val random = Random()

// http://stackoverflow.com/a/1241605/17339
fun poisson(lambda: Double): Int {
    val L = Math.exp(-lambda)
    var p = 1.0
    var k = 0

    do {
        k++
        p *= Math.random()
    } while (p > L)

    return k - 1
}

fun decayTime(count: Int, t: Instant, rate: Double, now: Instant): Int {
    if (count < 1) {
        return 0
    }

    val lambda = rate * (now.epochSecond - t.epochSecond)
    val k = poisson(lambda)

    if (k == -1) {
        throw IllegalStateException("Poisson simulation did not converge with rate of $rate, lambda $lambda")
    }

    return k
}
