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