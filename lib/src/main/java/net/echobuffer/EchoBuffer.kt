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
                echoLog.d("update realTTL:$realTTL lastTTL:$lastTTL")
            }
        }
    }

    private val batchActor = scope.actor<Set<S>>(capacity = capacity) {
        consumeEach {
            val intentToRequests = mutableSetOf<S>()
            val alreadyInCaches = mutableMapOf<S, R>()
            for (item in it) {
                val cache = getCache()[item]
                if (cache != null) {
                    alreadyInCaches[item] = cache
                } else {
                    intentToRequests.add(item)
                }
            }
            if (intentToRequests.isEmpty()) {
                echoLog.d("batch already in caches")
                responseChannel.offer(alreadyInCaches)
            } else {
                var resultMap: Map<S, R>? = null
                val realTTL = measureTimeMillis {
                    echoLog.d("batch requestDelegate $intentToRequests")
                    resultMap = withTimeoutOrNull(requestTimeoutMs) { requestDelegate.request(intentToRequests) }
                }
                resultMap?.let {
                    cache.putAll(it)
                    responseChannel.offer(it + alreadyInCaches)
                }
                lastTTL = realTTL.coerceIn(requestIntervalRange)
                echoLog.d("batchActor update realTTL:$realTTL lastTTL:$lastTTL")
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

    private inline fun sendAlreadyCacheToResponse(alreadyInCaches: MutableMap<S, R>) {
        if (alreadyInCaches.isNotEmpty()) {
            responseChannel.offer(alreadyInCaches)
        }
    }

    private suspend inline fun ActorScope<S>.fetchOneChannelDataToSet(intentToRequests: MutableSet<S>, alreadyInCaches: MutableMap<S, R>) {
        val e = channel.receive()
        val cache = getCache()[e]
        if (cache != null) {
            echoLog.d("already has cache")
            alreadyInCaches[e] = cache
        } else {
            echoLog.d("add to set")
            intentToRequests.add(e)
        }
    }

    private suspend inline fun ReceiveChannel<S>.fetchAllChannelDataToSet(intentToRequests: MutableSet<S>, alreadyInCaches: MutableMap<S, R>) {
        withTimeoutOrNull(lastTTL) {
            for (e in this@fetchAllChannelDataToSet) {
                val cache = getCache()[e]
                if (cache != null) {
                    echoLog.d("already has cache with timeout")
                    alreadyInCaches[e] = cache
                } else {
                    echoLog.d("add to set with timeout")
                    intentToRequests.add(e)
                }
                intentToRequests.add(e)
            }
        }
    }

    override fun send(data: S): Call<R> {
        val cacheValue = cache[data]
        if (cacheValue != null) {
            echoLog.d("hit the cache $data")
            return CacheCall(cacheValue)
        }

        val call = RequestCall(data, requestTimeoutMs)
        sendActor.offer(data)
        echoLog.d("sendActor sent $data")
        return call
    }

    override suspend fun sendBatch(data: Set<S>): Call<Map<S, R>> {
        val call = BatchRequestCall(data, requestTimeoutMs)
        batchActor.offer(data)
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
            return try {
                withTimeout(requestTimeoutMs) {
                    return@withTimeout responseChannel.openSubscription().consume {
                        for (map in this) {
                            val r = map[requestData]
                            if (r != null) {
                                echoLog.d("enqueueAwait return $requestData -> $r")
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

    inner class BatchRequestCall(private val requestData: Set<S>,
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
                    return@withTimeout responseChannel.openSubscription().consume {
                        for (map in this) {

                            if (map.keys.containsAll(requestData)) {
                                echoLog.d("batch enqueueAwait return $requestData -> $map")
                                return@consume map
                            } else continue
                        }
                        return@consume null
                    }
                }
            } catch (t: Throwable) {
                echoLog.d("batch enqueueAwait timeout $requestData")
                return null
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