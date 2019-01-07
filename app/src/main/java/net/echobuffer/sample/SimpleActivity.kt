package net.echobuffer.sample

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import kotlinx.android.synthetic.main.activity_simple.*
import kotlinx.coroutines.*
import net.echobuffer.RealEchoBuffer
import net.echobuffer.RequestDelegate
import net.echobuffer.sample.util.debugLog
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

class SimpleActivity : AppCompatActivity(), CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Job() + Dispatchers.Default


    val echoBuffer = RealEchoBuffer(object: RequestDelegate<Long, UserInfo>{
        override suspend fun request(data: Set<Long>): Map<Long, UserInfo> {
            debugLog("request is $data")
            delay(2000)
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

        send_btn.setOnClickListener {
            val key = Random(System.currentTimeMillis()).nextLong(99999999)
            val call = echoBuffer.send(key)
            debugLog("send $key")
            launch {
                val userInfo = call.enqueueAwait()
                debugLog("response is $userInfo")
            }
        }
    }
}

data class UserInfo(val uid: Long, val name: String)
