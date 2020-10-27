package net.echobuffer

import android.arch.lifecycle.MutableLiveData
import android.os.Looper

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
     * @param retry 重试次数
     */
    suspend fun enqueueAwaitOrNull(retry: Int = 0): R?

    /**
     * enqueue the request, post value to LiveData when success
     */
    fun enqueueLiveData(): MutableLiveData<R>
}

/**
 * Call already has cache
 */
class CacheCall<R>(private val cacheValue: R) : Call<R> {

    override fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit) {
        success(cacheValue)
    }

    override suspend fun enqueueAwaitOrNull(retry: Int): R? {
        return cacheValue
    }

    override fun enqueueLiveData(): MutableLiveData<R> {
        return MutableLiveData<R>().apply {
            setOrPostValue(cacheValue)
        }
    }
}

fun <T> MutableLiveData<T>.setOrPostValue(value: T) {
    if (Looper.myLooper() == Looper.getMainLooper())
        setValue(value)
    else
        postValue(value)
}

