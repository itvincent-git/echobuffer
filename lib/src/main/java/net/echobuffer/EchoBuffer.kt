package net.echobuffer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.first
import kotlinx.coroutines.channels.toSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 *
 * @author zhongyongsheng
 */
interface EchoBuffer<S, R> {

    fun send(data: S): Call<R>
}

interface RequestDelegate<S, R> {

    suspend fun request(data: Set<S>): Map<S, R>
}

class RealEchoBuffer<S, R>(protected val requestDelegate: RequestDelegate<S, R>): EchoBuffer<S, R> {
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

        startRequest()

        scope.async {
            requestChannel.send(data)
        }


        return RequestCall(data)
    }

    private fun startRequest() {
        if (isStartedRequest.compareAndSet(false, true)) {
            scope.async {
                while (true) {
                    debugLog("start get requestChannel")
                    val set = mutableSetOf<S>()
                    val response = withTimeoutOrNull(lastTTL) { requestChannel.receive() }
                    if (response == null) {
                        break
                    } else {
                        debugLog("start get requestChannel data $response")
                        val resultMap = requestDelegate.request(response)
                        for (entry in resultMap) {
                            responseChannel.send(entry)
                        }
                        delay(lastTTL)
                    }
                }
                isStartedRequest.compareAndSet(true, false)
            }
        }
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