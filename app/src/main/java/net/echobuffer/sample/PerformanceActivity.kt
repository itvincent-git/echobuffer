package net.echobuffer.sample

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import kotlinx.android.synthetic.main.activity_performance.*
import kotlinx.coroutines.*
import net.echobuffer.EchoBuffer
import net.echobuffer.RequestDelegate
import net.echobuffer.sample.util.debugLog
import net.echobuffer.sample.util.errorLog
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

class PerformanceActivity : AppCompatActivity(), CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Job() + Dispatchers.Default


    private val echoBufferRequest = EchoBuffer.createRequest(object: RequestDelegate<Long, UserInfo> {
        override suspend fun request(data: Set<Long>): Map<Long, UserInfo> {
            debugLog("createRequest is $data")
            delay(300)
            val map = mutableMapOf<Long, UserInfo>()
            for (item in data) {
                map[item] = UserInfo(item, "$item name")
            }
            return map
        }
    }, 100)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_performance)

        repeat_btn.setOnClickListener {
            val randomCeil = 10000L
            val random = Random(System.currentTimeMillis())
            repeat(10000) {
                val key = random.nextLong(randomCeil)
                val call = echoBufferRequest.send(key)
                debugLog("enqueueAwait $key")
                launch {
                    try {
                        withTimeout(2000) {
                            val userInfo = call.enqueueAwait()
                            debugLog("enqueueAwait response is $userInfo")
                        }
                    } catch (t: Throwable) {
                        errorLog("enqueueAwait response error $key", t)
                    }
                }
            }
        }

        res_btn.setOnClickListener {
            val randomCeil = 1000L
            val random = Random(System.currentTimeMillis())
            repeat(10000) {
                val key = random.nextLong(randomCeil)
                launch {
                    delay(it * 500L)
                    try {
                        //withTimeout(2000) {
                            val call = echoBufferRequest.send(key)
                            debugLog("test2 key:$key")
                            val userInfo = call.enqueueAwait()
                            withContext(Dispatchers.Main) {
                                debugLog("test2 response is $userInfo")
                            }
                        //}
                    } catch (t: Throwable) {
                        errorLog("test2 response error $key", t)
                    }
                }
            }
        }
    }
}
