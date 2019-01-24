package net.echobuffer

import android.arch.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis

/**
 * EchoBuffer entry
 * @author zhongyongsheng
 */
object EchoBuffer {
    /**
     * 构建request，用于发送数据
     *
     * @param requestDelegate
     * @param capacity
     * @param requestIntervalRange
     */
    fun <S, R> createRequest(requestDelegate: RequestDelegate<S, R>,
                             capacity: Int = 10,
                             requestIntervalRange: LongRange = LongRange(100L, 1000L),
                             maxCacheSize: Int = 256,
                             requestTimeoutMs: Long = 3000): EchoBufferRequest<S, R> {
        return RealEchoBufferRequest(requestDelegate, capacity, requestIntervalRange, maxCacheSize, requestTimeoutMs)
    }

    fun setLogImplementation(logImpl: EchoLogApi) {
        echoLog = logImpl
    }
}

/**
 * send request and return Call
 */
interface EchoBufferRequest<S, R> {
    fun send(data: S): Call<R>
    fun getCache(): Cache<S, R>
}

/**
 * The interface for the actual batch request data is optimized by EchoBuffer
 */
interface RequestDelegate<S, R> {

    /**
     * request use data as parameter.
     * Returns Map, key for data, and value for return value
     */
    suspend fun request(data: Set<S>): Map<S, R>?
}

private class RealEchoBufferRequest<S, R>(private val requestDelegate: RequestDelegate<S, R>,
                                          capacity: Int,
                                          requestIntervalRange: LongRange,
                                          maxCacheSize: Int,
                                          private val requestTimeoutMs: Long): EchoBufferRequest<S, R> {
    private val cache = RealCache<S, R>(maxCacheSize)
    private val responseChannel = BroadcastChannel<Map<S, R>>(capacity)
    private val scope = CoroutineScope( Job() + Dispatchers.IO)
    private var lastTTL = 100L
    private val sendActor = scope.actor<S>(capacity = capacity) {
        consume {
            echoLog.d("start consume")
            val set = mutableSetOf<S>()
            while (true) {
                if (set.isNotEmpty()) set.clear()
                val e = channel.receive()
                echoLog.d("add to set")
                set.add(e)

                withTimeoutOrNull(lastTTL) {
                    for (e in this@consume) {
                        echoLog.d("add to set with timeout")
                        set.add(e)
                    }
                }

                if (set.isEmpty()) continue
                var resultMap: Map<S, R>? = null
                val realTTL = measureTimeMillis {
                    resultMap = withTimeoutOrNull(requestTimeoutMs) { requestDelegate.request(set) }
                }
                resultMap?.let {
                    cache.putAll(it)
                    responseChannel.send(it)
                }
                lastTTL = realTTL.coerceIn(requestIntervalRange)
                echoLog.d("update realTTL:$realTTL lastTTL:$lastTTL")
            }
        }
    }

    override fun send(data: S): Call<R> {
        val cacheValue = cache[data]
        if (cacheValue != null) {
            echoLog.d("hit the cache")
            return CacheCall(cacheValue)
        }

        val call = RequestCall(data, requestTimeoutMs)
        scope.launch {
            echoLog.d("sendActor send before ${requestDelegate}>$data")
            sendActor.send(data)
            echoLog.d("sendActor sent")
        }
        return call
    }

    inner class RequestCall(private val requestData: S,
                            private val requestTimeoutMs: Long): Call<R> {
        override fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit) {
            scope.launch {
                val result = enqueueAwaitOrNull()
                if (result != null) success(result)
                else error(TimeoutException("request timeout"))
            }
        }

        override suspend fun enqueueAwaitOrNull(): R? {
            return withTimeoutOrNull(requestTimeoutMs) {
                return@withTimeoutOrNull responseChannel.openSubscription().consume {
                    for (map in this) {
                        echoLog.d("enqueueAwait on consume $requestData")
                        val r = map[requestData]
                        if (r != null) return@consume r else continue
                    }
                    return@consume null
                }
            }
        }

        override fun enqueueLiveData(): MutableLiveData<R> {
            return MutableLiveData<R>().apply {
                scope.launch {
                    val result = enqueueAwaitOrNull()
                    if (result != null) postValue(result)
                }
            }
        }
    }

    override fun getCache(): Cache<S, R> = cache
}