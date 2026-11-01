package com.litebrowser

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import id.or.oo.pr.engine.ProotHost
import java.io.File

class LiteApp : Application() {

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

        try {
            if (!DistroInstaller.isInstalled(this)) {
                Logger.i("App", "Alpine not installed yet, installing...")
                DistroInstaller.install(this) { msg ->
                    Logger.i("App", msg)
                }
            } else {
                Logger.i("App", "Alpine already installed")
            }
        } catch (e: Exception) {
            Logger.e("App", "DistroInstaller failed", e)
        }

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            Logger.crash(thread, ex)
        }
    }

    /**
     * پیاده‌سازی ProotHost برای این Application.
     * به جای اینکه LiteApp مستقیماً ProotHost رو پیاده‌سازی کنه (که با Application تداخل داره)،
     * یک adapter جداگانه می‌سازیم.
     */
    fun getProotHost(): ProotHost = object : ProotHost {
        override val prefixDir: File
            get() = File(filesDir, "usr").also { it.mkdirs() }

        override val homeDir: File
            get() = File(filesDir, "home").also { it.mkdirs() }

        override val cacheDir: File
            get() = this@LiteApp.cacheDir

        override val packageName: String
            get() = this@LiteApp.packageName

        override val dnsServers: List<String>
            get() {
                return try {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val network = cm.activeNetwork ?: return emptyList()
                    val linkProps = cm.getLinkProperties(network) ?: return emptyList()
                    linkProps.dnsServers.mapNotNull { it.hostAddress }
                } catch (e: Exception) {
                    emptyList()
                }
            }
    }
}
