package net.echobuffer.sample

import android.arch.lifecycle.Observer
import android.os.Bundle
import kotlinx.android.synthetic.main.activity_simple.enqueue_dontusecache_btn
import kotlinx.android.synthetic.main.activity_simple.send_bigdata_btn
import kotlinx.android.synthetic.main.activity_simple.send_enquene_btn
import kotlinx.android.synthetic.main.activity_simple.send_livedata_btn
import kotlinx.android.synthetic.main.activity_simple.send_multi_btn
import kotlinx.android.synthetic.main.activity_simple.send_wait_btn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.echobuffer.EchoBuffer
import net.echobuffer.RequestDelegate
import net.echobuffer.sample.util.debugLog
import net.echobuffer.sample.util.errorLog
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
    }, enableRequestDelegateInBatches = true, chunkSize = 8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple)

        val randomCeil = 10L
        send_wait_btn.setOnClickListener {
            val key = Random(System.currentTimeMillis()).nextLong(randomCeil)
            val call = echoBufferRequest.send(key)
            debugLog("enqueueAwait $key")
            launch {
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
            launch {
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
            launch {
                val call = echoBufferRequest.sendBatch(keys)
                val response = call.enqueueAwaitOrNull()
                debugLog("send bigdata response size:${response?.size}")
            }
        }

        enqueue_dontusecache_btn.setOnClickListener {
            val key = Random(System.currentTimeMillis()).nextLong(randomCeil)
            val call = echoBufferRequest.send(key, false)
            debugLog("enqueue don't use cache $key")
            launch {
                try {
                    val userInfo = call.enqueueAwaitOrNull()
                    debugLog("enqueue don't use cache response is $userInfo")
                } catch (t: Throwable) {
                    errorLog("enqueue don't use cache response error", t)
                }
            }
        }
    }
}

data class UserInfo(val uid: Long, val name: String)
