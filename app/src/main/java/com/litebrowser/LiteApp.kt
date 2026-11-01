package com.litebrowser

import android.app.Application

class LiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.reset()
        Logger.i("App", "========== APP START ==========")
        Logger.i("App", "Android SDK: ${android.os.Build.VERSION.SDK_INT}")
        Logger.i("App", "Device: ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        Logger.i("App", "Manufacturer: ${android.os.Build.MANUFACTURER}")
        Logger.i("App", "ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")

        // ثبت crash handler سراسری
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            Logger.crash(thread, ex)
            // اجازه بده سیستم هم هندل کنه
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, ex)
        }
    }
}
