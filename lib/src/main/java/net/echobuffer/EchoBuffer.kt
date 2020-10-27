package net.echobuffer

import android.arch.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ActorScope
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.stripe.lib.awaitOrNull
import net.stripe.lib.toSafeSendChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis

/**
 * EchoBuff is a set of tools for interface requests and data caching. Send the request by using send(request)
 * and return the result in three ways:
 * 1.enqueue(success, error) - callback method to receive data
 * 2.suspend enqueueAwaitOrNull() - The way to wait for await to return data
 * 3.enqueueLiveData(): MutableLiveData - returns the data of LiveData
 *
 * If the cache does not exist, the request interface obtains the data, otherwise it is obtained from the cache and is
 * uniformly returned using the above three interfaces.
 *
 * 注意：
 * - RTT如果没有超过requestTimeoutMs时，请求仍然出现超时并返回null，证明此时请求量太大导致capacity不够，可适当增加capacity增加请求缓冲
 *
 * @author zhongyongsheng
 */

/**
 * EchoBuffer entry
 */
@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
object EchoBuffer {
    /**
     * build EchoBufferRequest to send request
     *
     * @param requestDelegate 实际的批量请求处理
     * @param capacity 请求的缓冲区大小。如果请求量过多批量请求接口未能处理过来导致缓冲区超过capacity，则该请求会出现超时返回null。
     * @param requestIntervalRange 两次批量请求之间的间隔，设置为一个范围[x,y]，x代表下限，y代表上限，单位ms
     * @param maxCacheSize 最大缓存大小
     * @param enableRequestDelegateInBatches true 打开使用批量请求requestDelegate，然后等待结果返回再拼装成完整的数据
     * @param chunkSize 如果 enableRequestDelegateInBatches为true时，每次批量请求的大小
     * @param dispatcher 发送请求使用的dispatcher
     */
    fun <S, R> createRequest(
        requestDelegate: RequestDelegate<S, R>,
        capacity: Int = 1024,
        requestIntervalRange: LongRange = LongRange(100L, 1000L),
        maxCacheSize: Int = 256,
        requestTimeoutMs: Long = 3000,
        enableRequestDelegateInBatches: Boolean = false,
        chunkSize: Int = 64,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): EchoBufferRequest<S, R> {
        return RealEchoBufferRequest(requestDelegate, capacity,
            requestIntervalRange, maxCacheSize, requestTimeoutMs, enableRequestDelegateInBatches,
            chunkSize.coerceAtLeast(1),
            dispatcher)
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
    suspend fun sendBatch(data: Set<S>, useCache: Boolean = true): Call<Map<S, R>>
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

    /**
     * use default data when the response is null
     */
    fun createDefaultData(): R
}

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
private class RealEchoBufferRequest<S, R>(
    private val requestDelegate: RequestDelegate<S, R>,
    capacity: Int,
    private val requestIntervalRange: LongRange,
    maxCacheSize: Int,
    private val requestTimeoutMs: Long,
    val enableRequestDelegateInBatches: Boolean,
    val chunkSize: Int,
    dispatcher: CoroutineDispatcher
) : EchoBufferRequest<S, R> {
    private val cache = RealCache<S, R>(maxCacheSize)
    private val responseChannel = BroadcastChannel<Map<S, R>>(capacity)
    private val scope = CoroutineScope(Job() + dispatcher)
    private var lastRTT = requestIntervalRange.first//上一次请求回复的时长，默认值为设置的间隔小值

    @Volatile
    private var counter = 0L//单个查询的计数标记

    @Volatile
    private var batchCounter = 0L//批量查询的计数标记
    private val sendActor = scope.actor<SendActorData<S>>(capacity = capacity) {
        consume {
            echoLog.d("single start consume ${this@RealEchoBufferRequest.requestDelegate}")
            val intentToRequests = mutableSetOf<S>()
            val alreadyInCaches = mutableMapOf<S, R>()
            while (true) {
                if (intentToRequests.isNotEmpty()) intentToRequests.clear()
                if (alreadyInCaches.isNotEmpty()) alreadyInCaches.clear()
                fetchOneChannelDataToSet(intentToRequests, alreadyInCaches)
                fetchAllChannelDataToSet(intentToRequests, alreadyInCaches)
                sendAlreadyCacheToResponse(alreadyInCaches)
                if (intentToRequests.isEmpty()) continue
                requestDelegateToResChannel(intentToRequests)
                counter++
            }
        }
    }.toSafeSendChannel()

    private suspend fun requestDelegateToResChannel(intentToRequests: Set<S>): Long {
        var resultMap: Map<S, R>? = null
        val realRTT = measureTimeMillis {
            echoLog.d("single requestDelegate[$counter][size:${intentToRequests.size}] ${this@RealEchoBufferRequest
                .requestDelegate} $intentToRequests")
            if (!enableRequestDelegateInBatches || intentToRequests.size < chunkSize) {
                //关闭批量 或者 小于chunkSize就直接请求
                val delegateResponse = withTimeoutOrNull(requestTimeoutMs) {
                    requestDelegate.request(intentToRequests)
                }
                resultMap = transformMap(intentToRequests, delegateResponse)
            } else {
                val mergeMap = mutableMapOf<S, R>()
                intentToRequests.chunkRunMergeMap(mergeMap, chunkSize) {
                    val delegateResponse = requestDelegate.request(intentToRequests)
                    transformMap(intentToRequests, delegateResponse)
                }
                resultMap = mergeMap
            }
        }
        resultMap?.let {
            cache.putAll(it)
            responseChannel.offer(it)
            //成功请求能打印日志及更新rtt
            echoLog.d("single update realRTT:$realRTT lastRTT:$lastRTT [$counter] ${this@RealEchoBufferRequest
                .requestDelegate}")
            lastRTT = realRTT.coerceIn(requestIntervalRange)
        }
        return realRTT
    }

    /**
     * 返回一个新的Map<请求.key,回包.value>
     * - 如果delegateResponse超时，期望返回null
     * - 如果delegateResponse返回emptymap，期望返回<key,默认对象>
     */
    private fun transformMap(
        intentToRequests: Set<S>, delegateResponse: Map<S, R>?
    ): MutableMap<S, R>? {
        //如果查询的结果为null，是因为超时或其它异常，则不拼接原来的key
        if (delegateResponse == null) return null
        val transformMap = mutableMapOf<S, R>()
        intentToRequests.forEach {
            transformMap[it] = delegateResponse[it] ?: requestDelegate.createDefaultData()
        }
        return transformMap
    }

    /**
     * 将MutableSet拆分成chunkSize个set,分别调用block，返回的map合并一起再返回
     */
    suspend fun <K, V> Set<K>.chunkRunMergeMap(
        map: MutableMap<K, V>, chunkSize: Int, block: suspend (MutableSet<K>) ->
        Map<K, V>?
    ) {
        val set = splitSet(chunkSize)
        coroutineScope {
            forEachAsync(set) {
                block(it)
            }.forEach {
                it.awaitOrNull(requestTimeoutMs, TimeUnit.MILLISECONDS)?.apply {
                    map.putAll(this)
                }
            }
        }
    }

    private fun sendAlreadyCacheToResponse(alreadyInCaches: MutableMap<S, R>) {
        if (alreadyInCaches.isNotEmpty()) {
            responseChannel.offer(alreadyInCaches)
        }
    }

    private suspend inline fun ActorScope<SendActorData<S>>.fetchOneChannelDataToSet(
        intentToRequests: MutableSet<S>,
        alreadyInCaches: MutableMap<S, R>
    ) {
        val item = channel.receive()
        val e = item.requestData
        if (item.useCache) {
            val cache = getCache()[e]
            if (cache != null) {
                //echoLog.d("single already has cache ${item.requestData}")
                alreadyInCaches[e] = cache
                return
            }
        }
        //echoLog.d("single add to request ${item.requestData}")
        intentToRequests.add(e)
    }

    private suspend inline fun ReceiveChannel<SendActorData<S>>.fetchAllChannelDataToSet(
        intentToRequests: MutableSet<S>,
        alreadyInCaches: MutableMap<S, R>
    ) {
        withTimeoutOrNull(lastRTT) {
            for (item in this@fetchAllChannelDataToSet) {
                val e = item.requestData
                if (item.useCache) {
                    val cache = getCache()[e]
                    if (cache != null) {
                        //echoLog.d("single already has cache with ${item.requestData}")
                        alreadyInCaches[e] = cache
                        continue
                    }
                }
                //echoLog.d("single add to request ${item.requestData}")
                intentToRequests.add(e)
            }
        }
    }

    override fun send(data: S, useCache: Boolean): Call<R> {
        val cacheValue = cache[data]
        if (cacheValue != null && useCache) {
            echoLog.d("single hit the cache $data ${this@RealEchoBufferRequest.requestDelegate}")
            return CacheCall(cacheValue)
        }
        sendActor.offer(SendActorData(data, useCache))
        //echoLog.d("single sendActor sent $data [$counter] ${this@RealEchoBufferRequest
        //        .requestDelegate}")
        return RequestCall(data, requestTimeoutMs)
    }

    override suspend fun sendBatch(data: Set<S>, useCache: Boolean): Call<Map<S, R>> {
        return BatchRequestCall(data, requestTimeoutMs, useCache, batchCounter++)
    }

    private inner class RequestCall(
        private val requestData: S,
        private val requestTimeoutMs: Long
    ) : Call<R> {
        override fun enqueue(success: (R) -> Unit, error: (Throwable) -> Unit, retry: Int) {
            scope.launch {
                val result = enqueueAwaitOrNull(retry)
                if (result != null) success(result)
                else error(TimeoutException("single request timeout"))
            }
        }

        override suspend fun enqueueAwaitOrNull(retry: Int): R? {
            return try {
                withTimeout(requestTimeoutMs) {
                    return@withTimeout responseChannel.openSubscription().consume {
                        for (map in this) {
                            val r = map[requestData]
                            if (r != null) {
                                echoLog.d("single enqueueAwait return ${this@RealEchoBufferRequest
                                    .requestDelegate} $requestData")
                                return@consume r
                            } else continue
                        }
                        return@consume null
                    }
                }
            } catch (t: Throwable) {
                echoLog.d("single enqueueAwait error:${t.message} ${this@RealEchoBufferRequest
                    .requestDelegate} $requestData")
                if (retry > 0) {
                    return enqueueAwaitOrNull(retry - 1)
                }
                return null
            }
        }

        override fun enqueueLiveData(retry: Int): MutableLiveData<R> {
            return MutableLiveData<R>().apply {
                scope.launch {
                    val result = enqueueAwaitOrNull(retry)
                    if (result != null) postValue(result)
                }
            }
        }
    }

    private inner class BatchRequestCall(
        private val requestData: Set<S>,
        private val requestTimeoutMs: Long,
        private val useCache: Boolean = true,
        private val index: Long
    ) :
        Call<Map<S, R>> {

        override fun enqueue(success: (Map<S, R>) -> Unit, error: (Throwable) -> Unit, retry: Int) {
            scope.launch {
                val result = enqueueAwaitOrNull(retry)
                if (result != null) success(result)
                else error(TimeoutException("batch request timeout"))
            }
        }

        override suspend fun enqueueAwaitOrNull(retry: Int): Map<S, R>? {
            return try {
                withTimeout(requestTimeoutMs) {
                    return@withTimeout fetchBatchData()
                }
            } catch (t: Throwable) {
                echoLog.d("batch enqueueAwait:${t.message} [B$index]${this@RealEchoBufferRequest
                    .requestDelegate} $requestData")
                if (retry > 0) {
                    return enqueueAwaitOrNull(retry - 1)
                }
                return null
            }
        }

        private suspend fun fetchBatchData(): Map<S, R>? {
            val intentToRequests = mutableSetOf<S>()
            val alreadyInCaches = mutableMapOf<S, R>()

            if (useCache) {
                fetchInCache(alreadyInCaches, intentToRequests)
            } else {
                for (item in requestData) {
                    intentToRequests.add(item)
                }
            }

            if (intentToRequests.isEmpty()) {
                echoLog.d("batch already in caches")
                return alreadyInCaches
            } else {
                var resultMap: Map<S, R>? = null
                val realRTT = measureTimeMillis {
                    resultMap = transformMap(intentToRequests, fetchWithDelegate(intentToRequests))
                }
                resultMap?.let { map ->
                    cache.putAll(map)
                    val result = map + alreadyInCaches
                    echoLog.d("batch requestDelegate result [RTT:$realRTT] [B$index] ${this@RealEchoBufferRequest
                        .requestDelegate} $requestData")
                    return result
                }
            }
            return null
        }

        private suspend fun fetchWithDelegate(intentToRequests: MutableSet<S>): Map<S, R>? {
            echoLog.d("batch requestDelegate [B$index] [size:${intentToRequests.size}]${this@RealEchoBufferRequest
                .requestDelegate} $intentToRequests")
            if (!enableRequestDelegateInBatches) {
                return withTimeoutOrNull(requestTimeoutMs) {
                    requestDelegate.request(intentToRequests)
                }
            } else {
                //批量请求
                val mergeMap = mutableMapOf<S, R>()
                intentToRequests.chunkRunMergeMap(mergeMap, chunkSize) {
                    requestDelegate.request(it)
                }
                return if (mergeMap.isEmpty()) {
                    null
                } else {
                    mergeMap
                }
            }
        }

        private fun fetchInCache(
            alreadyInCaches: MutableMap<S, R>, intentToRequests: MutableSet<S>
        ) {
            for (item in requestData) {
                val cache = getCache()[item]
                if (cache != null) {
                    alreadyInCaches[item] = cache
                } else {
                    intentToRequests.add(item)
                }
            }
        }

        override fun enqueueLiveData(retry: Int): MutableLiveData<Map<S, R>> {
            return MutableLiveData<Map<S, R>>().apply {
                scope.launch {
                    val result = enqueueAwaitOrNull(retry)
                    if (result != null) postValue(result)
                }
            }
        }
    }

    override fun getCache(): Cache<S, R> = cache
}

internal data class SendActorData<S>(val requestData: S, val useCache: Boolean)

/**
 * 将一个MutableSet拆分成size个set，返回list
 */
inline fun <E> Set<E>.splitSet(size: Int): List<MutableSet<E>> {
    val list = mutableListOf<MutableSet<E>>()
    var tempSet: MutableSet<E>? = null
    for (item in this) {
        if (tempSet == null) {
            tempSet = mutableSetOf()
        }
        tempSet.add(item)
        if (tempSet.size >= size) {
            list.add(tempSet)
            tempSet = null
        }
    }
    if (tempSet != null) {
        list.add(tempSet)
    }
    return list
}

/**
 * 循环执行async，并返回全部的Deferred
 */
suspend fun <T, R> CoroutineScope.forEachAsync(
    iterable: Iterable<T>,
    block: suspend CoroutineScope.(T) -> R
): MutableList<Deferred<R>> {
    val list = mutableListOf<Deferred<R>>()
    iterable.forEach {
        list.add(async {
            block(it)
        })
    }
    return list
}