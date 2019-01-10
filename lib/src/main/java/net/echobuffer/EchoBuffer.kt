package net.echobuffer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consume
import kotlin.system.measureTimeMillis

/**
 * EchoBuffer入口类
 * @author zhongyongsheng
 */
object EchoBuffer {
    /**
     * 构建request，用于发送数据
     */
    fun <S, R> createRequest(requestDelegate: RequestDelegate<S, R>, capacity: Int = 10): EchoBufferRequest<S, R> {
        return RealEchoBufferRequest(requestDelegate, capacity)
    }

    fun setLogImplementation(logImpl: EchoLogApi) {
        echoLog = logImpl
    }
}

/**
 * 发送数据，返回Call
 */
interface EchoBufferRequest<S, R> {
    fun send(data: S): Call<R>
}

/**
 * EchoBuffer优化后，实际批量请求数据的接口
 */
interface RequestDelegate<S, R> {
    suspend fun request(data: Set<S>): Map<S, R>
}

class RealEchoBufferRequest<S, R>(private val requestDelegate: RequestDelegate<S, R>,
                                  capacity: Int = 10): EchoBufferRequest<S, R> {
    protected val cache = RealCache<S, R>()
    protected val responseChannel = BroadcastChannel<Map<S, R>>(capacity)
    protected val scope = CoroutineScope( Job() + Dispatchers.IO)
    protected var lastTTL = 100L
    protected val sendActor = scope.actor<S>(capacity = capacity) {
        while (true) {
            val set = mutableSetOf<S>()
            fetchItemWithTimeout(set, channel)
            var resultMap: Map<S, R>? = null
            lastTTL = measureTimeMillis {
                try { resultMap = requestDelegate.request(set) } finally { }
            }
            resultMap?.let {
                cache.putAll(it)
                responseChannel.send(it)
            }
            echoLog.d("update lastTTL: $lastTTL")
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
        scope.async {
            sendActor.send(data)
        }
        return call
    }

    inner class RequestCall(private val requestData: S): Call<R> {
        override fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit) {
            scope.async {
                responseChannel.openSubscription().consume {
                    for (map in this) {
                        val r = map[requestData]
                        if (r != null) success(r) else continue
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