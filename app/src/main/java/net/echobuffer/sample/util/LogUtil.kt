package net.echobuffer.sample.util

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * Created by zhongyongsheng on 2018/9/13.
 */
const val TAG = "LogUtil"

fun debugLog(msg: String) {
    Log.i(TAG, "$msg [${Thread.currentThread().name}]")
}

fun errorLog(message: String, throwable: Throwable) {
    Log.e(TAG, "${message} [${Thread.currentThread().name}]", throwable)
}

fun showToast(context: Context, text: String) {
    Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
}
