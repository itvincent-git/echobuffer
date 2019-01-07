package net.echobuffer

import kotlinx.coroutines.suspendCancellableCoroutine

/**
 *
 * @author zhongyongsheng
 */
interface Call<R> {
    fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit)
    suspend fun enqueueAwait(): R
}

class CacheCall<R>(val cacheValue: R): Call<R> {
    private var success: ((R) -> Unit)? = null
    private var error: ((Throwable) -> Unit)? = null

    override fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit) {
        return success(cacheValue)
    }

    override suspend fun enqueueAwait(): R {
        return cacheValue
    }

}

