package net.echobuffer

import kotlinx.coroutines.Deferred

/**
 *
 * @author zhongyongsheng
 */
interface Call<R> {
    fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit)
    suspend fun enqueueForDeferred(): Deferred<R>
}

class RealCall<R>(): Call<R> {
    private var success: ((R) -> Unit)? = null
    private var error: ((Throwable) -> Unit)? = null

    override fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit) {

    }

    override suspend fun enqueueForDeferred(): Deferred<R> {

    }

}