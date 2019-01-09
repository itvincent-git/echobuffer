package net.echobuffer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlin.system.measureTimeMillis

/**
 * EchoBuffer入口类
 * @author zhongyongsheng
 */
object EchoBuffer {
    /**
     * 构建request，用于发送数据
     */
    fun <S, R> createRequest(requestDelegate: RequestDelegate<S, R>): EchoBufferRequest<S, R> {
        return RealEchoBufferRequest(requestDelegate)
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

class RealEchoBufferRequest<S, R>(protected val requestDelegate: RequestDelegate<S, R>): EchoBufferRequest<S, R> {
    protected val cache = RealCache<S, R>()
    protected val responseChannel = Channel<Map.Entry<S, R>>()
    protected val scope = CoroutineScope( Job() + Dispatchers.IO)
    protected var lastTTL = 100L
    protected val actor = scope.actor<S> {
        while (true) {
            val set = mutableSetOf<S>()
            fetchItemWithTimeout(set, channel)
            lastTTL = measureTimeMillis {
                val resultMap = requestDelegate.request(set)
                for (entry in resultMap) {
                    cache.put(entry.key, entry.value)
                    responseChannel.send(entry)
                }
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
        echoLog.d("actor receive $item")
    }

    override fun send(data: S): Call<R> {
        val cacheValue = cache[data]
        if (cacheValue != null) {
            echoLog.d("hit the cache")
            return CacheCall(cacheValue)
        }
        scope.async {
            actor.send(data)
        }
        return RequestCall(data)
    }

    inner class RequestCall(protected val requestData: S): Call<R> {
        override fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit) {
            scope.async {
                while (true) {
                    val entry = responseChannel.receive()
                    if (entry.key == requestData) {
                        success(entry.value)
                        return@async
                    }
                }
                error(NoSuchElementException("cannot find match element, key is $requestData"))
            }
        }

        override suspend fun enqueueAwait(): R {
            return scope.async {
                while (true) {
                    val entry = responseChannel.receive()
                    if (entry.key == requestData) {
                        return@async entry.value
                    }
                }
                throw NoSuchElementException("cannot find match element, key is $requestData")
            }.await()
        }
    }

}