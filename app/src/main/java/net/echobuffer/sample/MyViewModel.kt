package net.echobuffer.sample

import android.arch.lifecycle.ViewModel
import kotlinx.coroutines.*
import net.echobuffer.EchoBuffer
import net.echobuffer.RequestDelegate
import net.echobuffer.sample.util.debugLog
import net.echobuffer.sample.util.errorLog
import kotlin.random.Random

/**
 * Created by zhongyongsheng on 2019/1/18.
 */
class MyViewModel: ViewModel() {
    val randomCeil = 100L
    val random = Random(System.currentTimeMillis())

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

    fun launchTest() {
        GlobalScope.launch {
            val key = random.nextLong(randomCeil)
            val key2 = random.nextLong(randomCeil)
            try {
                //withTimeout(2000) {
                val userInfo = echoBufferRequest.send(key).enqueueAwait()
                debugLog("test2 key:$key")
                withContext(Dispatchers.Main) {
                    debugLog("test2 response is $userInfo")
                }

                val userInfo2 = echoBufferRequest.send(key2).enqueueAwait()
                debugLog("test2 key2:$key2")
                withContext(Dispatchers.Main) {
                    debugLog("test2 response2 is $userInfo2")
                }

                debugLog("test2 response after")
                //}
            } catch (t: Throwable) {
                errorLog("test2 response error $key", t)
            }
        }
    }
}