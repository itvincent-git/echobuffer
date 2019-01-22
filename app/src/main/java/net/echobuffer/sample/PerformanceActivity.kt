package net.echobuffer.sample

import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import kotlinx.android.synthetic.main.activity_performance.*
import kotlinx.coroutines.*
import net.echobuffer.EchoBuffer
import net.echobuffer.RequestDelegate
import net.echobuffer.sample.util.debugLog
import net.echobuffer.sample.util.errorLog
import kotlin.random.Random

class PerformanceActivity : BaseActivity() {
    val randomCeil = 100L
    val random = Random(System.currentTimeMillis())
    var viewModel: MyViewModel? = null

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
        viewModel = ViewModelProviders.of(this).get(MyViewModel::class.java)

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
                            val userInfo = call.enqueueAwaitOrNull()
                            debugLog("enqueueAwait response is $userInfo")
                        }
                    } catch (t: Throwable) {
                        errorLog("enqueueAwait response error $key", t)
                    }
                }
            }
        }

        res_btn.setOnClickListener {
            it.postDelayed(runnable, 500)
        }
    }


    val runnable = object: Runnable {
        override fun run() {
            viewModel?.launchTest()
            res_btn.removeCallbacks(this)
            res_btn.postDelayed(this, 500)
        }
    }
}
