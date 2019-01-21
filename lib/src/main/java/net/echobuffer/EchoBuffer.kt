package net.echobuffer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consume
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
                             maxCacheSize: Int = 256): EchoBufferRequest<S, R> {
        return RealEchoBufferRequest(requestDelegate, capacity, requestIntervalRange, maxCacheSize)
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
                                          maxCacheSize: Int): EchoBufferRequest<S, R> {
    protected val cache = RealCache<S, R>(maxCacheSize)
    protected val responseChannel = BroadcastChannel<Map<S, R>>(capacity)
    protected val scope = CoroutineScope( Job() + Dispatchers.IO)
    protected var lastTTL = 100L
    protected val sendActor = scope.actor<S>(capacity = capacity) {
        while (true) {
            try {
                val set = mutableSetOf<S>()
                fetchItemWithTimeout(set, channel)
                var resultMap: Map<S, R>? = null
                val realTTL = measureTimeMillis {
                    resultMap = requestDelegate.request(set)
                }
                resultMap?.let {
                    cache.putAll(it)
                    responseChannel.send(it)
                }
                lastTTL = requestIntervalRange.closeValueInRange(realTTL)
                echoLog.d("update realTTL:$realTTL lastTTL:$lastTTL")
            } catch (t: Throwable) {
                echoLog.e("actor error", t)
            }
        }
    }

    private suspend fun fetchItemWithTimeout(set: MutableSet<S>, channel: Channel<S>){
        fetchItem(set, channel)
        withTimeoutOrNull(lastTTL) {
            while (true) {
                fetchItem(set, channel)
            }
        }
    }

    private suspend fun fetchItem(set: MutableSet<S>, channel: Channel<S>) {
        val item = channel.receive()
        set.add(item)
        echoLog.d("sendActor receive $item")
    }

    override fun send(data: S): Call<R> {
        val cacheValue = cache[data]
        if (cacheValue != null) {
            echoLog.d("hit the cache")
            return CacheCall(cacheValue)
        }

        val call = RequestCall(data)
        scope.launch {
            sendActor.send(data)
        }
        return call
    }

    inner class RequestCall(private val requestData: S): Call<R> {
        override fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit) {
            scope.launch {
                responseChannel.openSubscription().consume {
                    for (map in this) {
                        val r = map[requestData]
                        if (r != null) {
                            success(r)
                            return@launch
                        } else {
                            continue
                        }
                    }
                    error(NoSuchElementException("cannot find match element, key is $requestData"))
                }
            }
        }

        @Throws(NoSuchElementException::class)
        override suspend fun enqueueAwait(): R {
            responseChannel.openSubscription().consume {
                for (map in this) {
                    val r = map[requestData]
                    if (r != null) return r else continue
                }
            }
            throw NoSuchElementException("cannot find match element, key is $requestData")
        }
    }

}

/**
 * Value returns if it is within the current range, otherwise returns the boundary value
 */
fun LongRange.closeValueInRange(value: Long): Long {
    if (value < start) return start
    else if (value > endInclusive) return endInclusive
    else return value
}