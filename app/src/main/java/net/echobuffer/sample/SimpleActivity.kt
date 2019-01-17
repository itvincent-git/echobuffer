package net.echobuffer.sample

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_simple.*
import kotlinx.coroutines.*
import net.echobuffer.EchoBuffer
import net.echobuffer.RequestDelegate
import net.echobuffer.sample.util.debugLog
import net.echobuffer.sample.util.errorLog
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

class SimpleActivity : AppCompatActivity(), CoroutineScope {
    val job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Default

    private val echoBufferRequest = EchoBuffer.createRequest(object: RequestDelegate<Long, UserInfo>{
        override suspend fun request(data: Set<Long>): Map<Long, UserInfo> {
            debugLog("createRequest is $data")
            delay(300)
            val map = mutableMapOf<Long, UserInfo>()
            for (item in data) {
                map[item] = UserInfo(item, "$item name")
            }
            return map
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple)

        val randomCeil = 9L
        send_wait_btn.setOnClickListener {
            val key = Random(System.currentTimeMillis()).nextLong(randomCeil)
            val call = echoBufferRequest.send(key)
            debugLog("enqueueAwait $key")
            launch {
                try {
                    val userInfo = call.enqueueAwait()
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
            call.enqueue( {
                debugLog("enqueue response is $it")
            }, {
                errorLog("enqueue response error", it)
            })

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}

data class UserInfo(val uid: Long, val name: String)
