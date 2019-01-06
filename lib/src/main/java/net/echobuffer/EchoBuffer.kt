package net.echobuffer

/**
 *
 * @author zhongyongsheng
 */
interface EchoBuffer<S, R> {

    fun send(data: S): Call<R>
}

interface RequestDelegate<S, R> {
    suspend fun request(data: S): R
}

abstract class AbstractEchoBuffer<S, R>(): EchoBuffer<S, R> {
    val cache = RealCache<S, R>()

    override fun send(data: S): Call<R> {
        val cacheValue = cache[data]
        if (cacheValue != null) {
            val call = RealCall<R>()
            return call
        }
    }
}