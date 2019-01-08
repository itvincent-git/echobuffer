package net.echobuffer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

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
    protected val requestChannel = Channel<S>()
    protected val responseChannel = Channel<Map.Entry<S, R>>()
    protected val scope = CoroutineScope( Job() + Dispatchers.IO)
    protected val lastTTL = 100L
    protected val isStartedRequest = AtomicBoolean(false)

    override fun send(data: S): Call<R> {
        val cacheValue = cache[data]
        if (cacheValue != null) {
            return CacheCall(cacheValue)
        }
        scope.async {
            requestChannel.offer(data)
            debugLog("after send")
            startRequest()
        }
        return RequestCall(data)
    }

    private fun startRequest() {
        if (isStartedRequest.compareAndSet(false, true)) {
            scope.async {
                while (true) {
                    //debugLog("start get requestChannel")
                    val set = fetchRequests()
                    if (set.isEmpty()) {
                        break
                    }
                    val resultMap = requestDelegate.request(set)
                    for (entry in resultMap) {
                        responseChannel.send(entry)
                    }
                    delay(lastTTL)
                }
                isStartedRequest.compareAndSet(true, false)
            }
        }
    }

    private fun fetchRequests(): Set<S> {
        var recv: S?
        val set = mutableSetOf<S>()
        do {
            recv =  requestChannel.poll()
            recv?.let {
                set.add(it)
            }
        } while (recv != null)
        debugLog("start get requestChannel data $set")
        return set
    }


    inner class RequestCall(protected val requestData: S): Call<R> {

        override fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit) {

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