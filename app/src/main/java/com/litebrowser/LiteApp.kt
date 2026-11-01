package com.litebrowser

import android.app.Application
import id.or.oo.pr.engine.ProotHost
import java.io.File

class LiteApp : Application(), ProotHost {
    override fun onCreate() {
        super.onCreate()
        Logger.reset()
        Logger.i("App", "========== APP START ==========")
        Logger.i("App", "Android SDK: ${android.os.Build.VERSION.SDK_INT}")
        Logger.i("App", "Device: ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        Logger.i("App", "ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")

        try {
            NativeInstaller.install(this)
        } catch (e: Exception) {
            Logger.e("App", "NativeInstaller failed", e)
        }

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            Logger.crash(thread, ex)
        }
    }

    // ===== پیاده‌سازی ProotHost =====
    override val prefixDir: File
        get() = File(filesDir, "usr")

    override val homeDir: File
        get() = File(filesDir, "home")

    override val cacheDir: File
        get() = cacheDir

    // packageName از خود Application ارث‌بری می‌شود
}
