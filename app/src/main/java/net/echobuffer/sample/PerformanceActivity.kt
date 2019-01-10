package net.echobuffer.sample

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
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
            delay(100)
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
}
