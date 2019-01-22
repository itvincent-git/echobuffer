package net.echobuffer.sample

import android.app.Application
import kotlinx.coroutines.Dispatchers

/**
 * Created by zhongyongsheng on 2019/1/17.
 */
class MyApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        //use CommonPool instead of DefaultScheduler
        //System.setProperty("kotlinx.coroutines.scheduler", "off")

        //add coroutine number to the threadname
        System.setProperty("kotlinx.coroutines.debug", if (BuildConfig.DEBUG) "on" else "off")

        Dispatchers.Default
    }
}