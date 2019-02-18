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
@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
object EchoBuffer {
    /**
     * build EchoBufferRequest to send request
     *
     * @param requestDelegate
     * @param capacity
     * @param requestIntervalRange
     */
    fun <S, R> createRequest(requestDelegate: RequestDelegate<S, R>,
                             capacity: Int = 10,
                             requestIntervalRange: LongRange = LongRange(100L, 1000L),
                             maxCacheSize: Int = 256,
                             requestTimeoutMs: Long = 3000,
                             dispatcher: CoroutineDispatcher = newSingleThreadContext("EchoBuffer")): EchoBufferRequest<S, R> {
        return RealEchoBufferRequest(requestDelegate, capacity, requestIntervalRange, maxCacheSize, requestTimeoutMs, dispatcher)
    }

    fun setLogImplementation(logImpl: EchoLogApi) {
        echoLog = logImpl
    }
}

/**
 * send request and return Call
 */
interface EchoBufferRequest<S, R> {
    fun send(data: S, useCache: Boolean = true): Call<R>
    suspend fun sendBatch(data: Set<S>): Call<Map<S, R>>
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

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
private class RealEchoBufferRequest<S, R>(private val requestDelegate: RequestDelegate<S, R>,
                                          capacity: Int,
                                          private val requestIntervalRange: LongRange,
                                          maxCacheSize: Int,
                                          private val requestTimeoutMs: Long,
                                          dispatcher: CoroutineDispatcher): EchoBufferRequest<S, R> {
    private val cache = RealCache<S, R>(maxCacheSize)
    private val responseChannel = BroadcastChannel<Map<S, R>>(Channel.CONFLATED)
    private val scope = CoroutineScope( Job() + dispatcher)
    private var lastTTL = 100L
    private val sendActor = scope.actor<SendActorData<S>>(capacity = capacity) {
        consume {
            echoLog.d("start consume")
            val intentToRequests = mutableSetOf<S>()
            val alreadyInCaches = mutableMapOf<S, R>()
            while (true) {
                if (intentToRequests.isNotEmpty()) intentToRequests.clear()
                if (alreadyInCaches.isNotEmpty()) alreadyInCaches.clear()
                fetchOneChannelDataToSet(intentToRequests, alreadyInCaches)
                fetchAllChannelDataToSet(intentToRequests, alreadyInCaches)
                sendAlreadyCacheToResponse(alreadyInCaches)
                if (intentToRequests.isEmpty()) continue
                val realTTL = requestDelegateToResChannel(intentToRequests)
                lastTTL = realTTL.coerceIn(requestIntervalRange)
                echoLog.d("update realTTL:$realTTL lastTTL:$lastTTL $requestDelegate")
            }
        }
    }

    private suspend fun requestDelegateToResChannel(intentToRequests: Set<S>): Long {
        var resultMap: Map<S, R>? = null
        val realTTL = measureTimeMillis {
            echoLog.d("requestDelegate $intentToRequests")
            resultMap = withTimeoutOrNull(requestTimeoutMs) { requestDelegate.request(intentToRequests) }
        }
        resultMap?.let {
            cache.putAll(it)
            responseChannel.offer(it)
        }
        return realTTL
    }

    private fun sendAlreadyCacheToResponse(alreadyInCaches: MutableMap<S, R>) {
        if (alreadyInCaches.isNotEmpty()) {
            responseChannel.offer(alreadyInCaches)
        }
    }

    private suspend inline fun ActorScope<SendActorData<S>>.fetchOneChannelDataToSet(intentToRequests: MutableSet<S>, alreadyInCaches: MutableMap<S, R>) {
        val item = channel.receive()
        val e = item.requestData
        if (item.useCache) {
            val cache = getCache()[e]
            if (cache != null) {
                echoLog.d("already has cache")
                alreadyInCaches[e] = cache
                return
            }
        }
        echoLog.d("add to set")
        intentToRequests.add(e)
    }

    private suspend inline fun ReceiveChannel<SendActorData<S>>.fetchAllChannelDataToSet(intentToRequests: MutableSet<S>, alreadyInCaches: MutableMap<S, R>) {
        withTimeoutOrNull(lastTTL) {
            for (item in this@fetchAllChannelDataToSet) {
                val e = item.requestData
                if (item.useCache) {
                    val cache = getCache()[e]
                    if (cache != null) {
                        echoLog.d("already has cache with timeout")
                        alreadyInCaches[e] = cache
                        continue
                    }
                }
                echoLog.d("add to set with timeout")
                intentToRequests.add(e)
            }
        }
    }

    override fun send(data: S, useCache: Boolean): Call<R> {
        val cacheValue = cache[data]
        if (cacheValue != null && useCache) {
            echoLog.d("hit the cache $data")
            return CacheCall(cacheValue)
        }
        sendActor.offer(SendActorData(data, useCache))
        echoLog.d("sendActor sent $data")
        return RequestCall(data, requestTimeoutMs)
    }

    override suspend fun sendBatch(data: Set<S>): Call<Map<S, R>> {
        return BatchRequestCall(data, requestTimeoutMs)
    }

    private inner class RequestCall(private val requestData: S,
                                    private val requestTimeoutMs: Long): Call<R> {
        override fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit) {
            scope.launch {
                val result = enqueueAwaitOrNull()
                if (result != null) success(result)
                else error(TimeoutException("request timeout"))
            }
        }

        override suspend fun enqueueAwaitOrNull(): R? {
            return try {
                withTimeout(requestTimeoutMs) {
                    return@withTimeout responseChannel.openSubscription().consume {
                        for (map in this) {
                            val r = map[requestData]
                            if (r != null) {
                                echoLog.d("enqueueAwait return $requestData ${this@RealEchoBufferRequest.requestDelegate}")
                                return@consume r
                            } else continue
                        }
                        return@consume null
                    }
                }
            } catch (t: Throwable) {
                echoLog.d("enqueueAwait timeout $requestData")
                return null
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

    private inner class BatchRequestCall(private val requestData: Set<S>,
                                         private val requestTimeoutMs: Long): Call<Map<S, R>> {

        override fun enqueue(success: (Map<S, R>) -> Unit, error: (Throwable) -> Unit) {
            scope.launch {
                val result = enqueueAwaitOrNull()
                if (result != null) success(result)
                else error(TimeoutException("batch request timeout"))
            }
        }

        override suspend fun enqueueAwaitOrNull(): Map<S, R>? {
            return try {
                withTimeout(requestTimeoutMs) {
                    return@withTimeout fetchBatchData()
                }
            } catch (t: Throwable) {
                echoLog.d("batch enqueueAwait timeout $requestData")
                return null
            }
        }

        private suspend fun fetchBatchData(): Map<S, R>? {
            val intentToRequests = mutableSetOf<S>()
            val alreadyInCaches = mutableMapOf<S, R>()
            fetchInCache(alreadyInCaches, intentToRequests)
            if (intentToRequests.isEmpty()) {
                echoLog.d("batch already in caches")
                return alreadyInCaches
            } else {
                var resultMap: Map<S, R>? = fetchWithDelegate(intentToRequests)
                resultMap?.let { map ->
                    cache.putAll(map)
                    val result = map + alreadyInCaches
                    echoLog.d("batch requestDelegate result $requestData ${this@RealEchoBufferRequest.requestDelegate}")
                    return result
                }
            }
            return null
        }

        private suspend fun fetchWithDelegate(intentToRequests: MutableSet<S>): Map<S, R>? {
            var resultMap: Map<S, R>? = null
            val realTTL = measureTimeMillis {
                echoLog.d("batch requestDelegate $intentToRequests")
                resultMap = withTimeoutOrNull(requestTimeoutMs) { requestDelegate.request(intentToRequests) }
            }
            echoLog.d("batch request realTTL:$realTTL")
            return resultMap
        }

        private fun fetchInCache(alreadyInCaches: MutableMap<S, R>, intentToRequests: MutableSet<S>) {
            for (item in requestData) {
                val cache = getCache()[item]
                if (cache != null) {
                    alreadyInCaches[item] = cache
                } else {
                    intentToRequests.add(item)
                }
            }
        }

        override fun enqueueLiveData(): MutableLiveData<Map<S, R>> {
            return MutableLiveData<Map<S, R>>().apply {
                scope.launch {
                    val result = enqueueAwaitOrNull()
                    if (result != null) postValue(result)
                }
            }
        }
    }

    override fun getCache(): Cache<S, R> = cache
}

internal data class SendActorData<S>(val requestData: S, val useCache: Boolean)