package net.echobuffer.sample

import android.app.Application

/**
 * Created by zhongyongsheng on 2019/1/17.
 */
class MyApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        //use CommonPool instead of DefaultScheduler
        System.setProperty("kotlinx.coroutines.scheduler", "off")
    }
}