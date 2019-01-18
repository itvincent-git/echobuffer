package net.echobuffer

import android.util.Log
import net.stripe.lib.BuildConfig

const val TAG = "EchoLog"
/**
 * User-defined logging implementation
 * Created by zhongyongsheng on 2019/1/7.
 */
internal var echoLog = object : EchoLogApi {
    override fun enableLog(): Boolean {
        return BuildConfig.DEBUG
    }

    override fun d(msg: String) {
        if (enableLog()) {
            Log.i(TAG, "$msg [${Thread.currentThread().name}]")
        }
    }

    override fun e(message: String, throwable: Throwable) {
        if (enableLog()) {
            Log.e(TAG, "${message} [${Thread.currentThread().name}]", throwable)
        }
    }

}

/**
 * User-defined logging interface
 */
interface EchoLogApi {
    fun d(msg: String)
    fun e(message: String, throwable: Throwable)
    fun enableLog(): Boolean
}
