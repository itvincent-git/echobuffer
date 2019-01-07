package net.echobuffer

import android.util.Log

/**
 * Created by zhongyongsheng on 2019/1/7.
 */
const val TAG = "EchoLog"
const val ISDEBUG = true

fun debugLog(msg: String) {
    if (ISDEBUG) {
        Log.i(TAG, "[${Thread.currentThread().name}] $msg")
    }
}

fun errorLog(message: String, throwable: Throwable) {
    Log.e(TAG, "[${Thread.currentThread().name}] ${message}", throwable)
}