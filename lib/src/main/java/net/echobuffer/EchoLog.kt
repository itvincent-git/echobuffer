package net.echobuffer

import android.util.Log

const val TAG = "EchoLog"
/**
 * User-defined logging implementation
 * Created by zhongyongsheng on 2019/1/7.
 */
internal var echoLog = object : EchoLogApi {
    override fun enableLog(): Boolean {
        return true
    }

    override fun d(msg: String) {
        if (enableLog()) {
            Log.i(TAG, "[${Thread.currentThread().name}] $msg")
        }
    }

    override fun e(message: String, throwable: Throwable) {
        if (enableLog()) {
            Log.e(TAG, "[${Thread.currentThread().name}] ${message}", throwable)
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
