package net.echobuffer

/**
 * The return object of then send method
 * @author zhongyongsheng
 */
interface Call<R> {
    /**
     * enqueue the request，callback invoke when success or error，callback will run in [non UI] thread
     */
    fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit)

    /**
     * enqueue the request, and await until success, or throw exception when error
     */
    suspend fun enqueueAwait(): R
}

class CacheCall<R>(private val cacheValue: R): Call<R> {

    override fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit) {
        return success(cacheValue)
    }

    override suspend fun enqueueAwait(): R {
        return cacheValue
    }
}

