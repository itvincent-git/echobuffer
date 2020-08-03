package net.echobuffer.sample

import android.arch.lifecycle.Observer
import android.os.Bundle
import kotlinx.android.synthetic.main.activity_simple.enqueue_dontusecache_btn
import kotlinx.android.synthetic.main.activity_simple.return_partial_batch_data_btn
import kotlinx.android.synthetic.main.activity_simple.return_partial_data_btn
import kotlinx.android.synthetic.main.activity_simple.send_bigdata_btn
import kotlinx.android.synthetic.main.activity_simple.send_enquene_btn
import kotlinx.android.synthetic.main.activity_simple.send_livedata_btn
import kotlinx.android.synthetic.main.activity_simple.send_multi_btn
import kotlinx.android.synthetic.main.activity_simple.send_wait_btn
import kotlinx.android.synthetic.main.activity_simple.single_batch_btn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.echobuffer.EchoBuffer
import net.echobuffer.RequestDelegate
import net.echobuffer.forEachAsync
import net.echobuffer.sample.util.debugLog
import net.echobuffer.sample.util.errorLog
import net.stripe.lib.awaitOrNull
import net.stripe.lib.lifecycleScope
import kotlin.random.Random

class SimpleActivity : BaseActivity() {

    private val echoBufferRequest = EchoBuffer.createRequest(object : RequestDelegate<Long, UserInfo> {
        override suspend fun request(data: Set<Long>): Map<Long, UserInfo> {
            debugLog("createRequest is $data")
//            delay(999999999999999999)
            delay(500)
            val map = mutableMapOf<Long, UserInfo>()
            for (item in data) {
                map[item] = UserInfo(item, "$item name")
            }
            return map
        }

        override fun createDefaultData() = UserInfo(-1, "")
    }, enableRequestDelegateInBatches = true, chunkSize = 8)

    private val returnPartialDataRequest = EchoBuffer.createRequest(object : RequestDelegate<Long, UserInfo> {
        override suspend fun request(data: Set<Long>): Map<Long, UserInfo> {
            debugLog("returnPartialDataRequest is $data")
            delay(500)
            val map = mutableMapOf<Long, UserInfo>()
            data.forEachIndexed { index, item ->
                if (index > data.size / 2) return@forEachIndexed
                map[item] = UserInfo(item, "$item name")
            }
            return map
        }

        override fun createDefaultData() = UserInfo(-1, "")
    }, enableRequestDelegateInBatches = true, chunkSize = 8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple)

        val randomCeil = 10L
        send_wait_btn.setOnClickListener {
            val key = Random(System.currentTimeMillis()).nextLong(randomCeil)
            val call = echoBufferRequest.send(key)
            debugLog("enqueueAwait $key")
            lifecycleScope.launch {
                try {
                    val userInfo = call.enqueueAwaitOrNull()
                    withContext(Dispatchers.Main) {
                        //do something in UI
                    }
                    debugLog("enqueueAwait response is $userInfo")
                } catch (t: Throwable) {
                    errorLog("enqueueAwait response error", t)
                }
            }
        }

        send_enquene_btn.setOnClickListener {
            val key = Random(System.currentTimeMillis()).nextLong(randomCeil)
            val call = echoBufferRequest.send(key)
            debugLog("enqueue send $key")
            call.enqueue({
                debugLog("enqueue response is $it")
            }, {
                errorLog("enqueue response error", it)
            })
        }

        send_livedata_btn.setOnClickListener {
            val key = Random(System.currentTimeMillis()).nextLong(randomCeil)
            val call = echoBufferRequest.send(key)
            debugLog("enqueuelivedata send $key")
            val liveData = call.enqueueLiveData()
            debugLog("enqueuelivedata getValue() ${liveData.value}")
            liveData.observe(this, Observer {
                debugLog("enqueuelivedata response is $it")
            })
        }

        send_multi_btn.setOnClickListener {
            val random = Random(System.currentTimeMillis())
            val keys = setOf(random.nextLong(randomCeil), random.nextLong(randomCeil), random.nextLong(randomCeil),
                random.nextLong(randomCeil), random.nextLong(randomCeil))
            debugLog("send multi send $keys")
            lifecycleScope.launch {
                val call = echoBufferRequest.sendBatch(keys)
                val response = call.enqueueAwaitOrNull()
                debugLog("send multi response is $response")
            }
        }

        send_bigdata_btn.setOnClickListener {
            val bigRandomCeil = 100000L
            val random = Random(System.currentTimeMillis())
            val keys = mutableSetOf<Long>()
            for (i in 0..29) {
                keys.add(random.nextLong(bigRandomCeil))
            }
            debugLog("send bigdata send $keys")
            lifecycleScope.launch {
                val call = echoBufferRequest.sendBatch(keys)
                val response = call.enqueueAwaitOrNull()
                debugLog("send bigdata response size:${response?.size}")
            }
        }

        enqueue_dontusecache_btn.setOnClickListener {
            val key = Random(System.currentTimeMillis()).nextLong(randomCeil)
            val call = echoBufferRequest.send(key, false)
            debugLog("enqueue don't use cache $key")
            lifecycleScope.launch {
                try {
                    val userInfo = call.enqueueAwaitOrNull()
                    debugLog("enqueue don't use cache response is $userInfo")
                } catch (t: Throwable) {
                    errorLog("enqueue don't use cache response error", t)
                }
            }
        }

        single_batch_btn.setOnClickListener {
            lifecycleScope.launch {
                val bigRandomCeil = 100000L
                val random = Random(System.currentTimeMillis())
                val keys = mutableSetOf<Long>()
                val results = mutableSetOf<UserInfo?>()
                for (i in 0..29) {
                    val key = random.nextLong(bigRandomCeil)
                    keys.add(key)

//                    if (i % 7 == 6) {
//                        delay(500)
//                    }
                }
                forEachAsync(keys) {
                    echoBufferRequest.send(it).enqueueAwaitOrNull()
                }.forEach {
                    it.awaitOrNull()?.apply {
                        results.add(this)
                    }
                }
                debugLog("single batch send $keys")
                debugLog("single batch response size:${results.size}")
            }
        }

        //只返回部分请求id的数据
        return_partial_data_btn.setOnClickListener {
            val key = Random(System.currentTimeMillis()).nextLong(randomCeil)
            val call = returnPartialDataRequest.send(key)
            debugLog("returnPartialDataRequest enqueueAwait $key")
            lifecycleScope.launch {
                try {
                    val userInfo = call.enqueueAwaitOrNull()
                    debugLog("returnPartialDataRequest enqueueAwait response is $userInfo")
                } catch (t: Throwable) {
                    errorLog("returnPartialDataRequest enqueueAwait response error", t)
                }
            }
        }

        //批量请求，只返回部分请求id的数据
        return_partial_batch_data_btn.setOnClickListener {
            val random = Random(System.currentTimeMillis())
            val keys = setOf(random.nextLong(randomCeil), random.nextLong(randomCeil), random.nextLong(randomCeil),
                random.nextLong(randomCeil), random.nextLong(randomCeil))
            debugLog("returnPartialDataRequest send multi send $keys")
            lifecycleScope.launch {
                val call = returnPartialDataRequest.sendBatch(keys)
                val response = call.enqueueAwaitOrNull()
                debugLog("returnPartialDataRequest send multi response is $response")
            }
        }
    }
}

data class UserInfo(val uid: Long, val name: String)
