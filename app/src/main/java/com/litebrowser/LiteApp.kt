package com.litebrowser

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
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

    // ===== ProotHost implementation =====

    override val prefixDir: File
        get() = File(filesDir, "usr").also { it.mkdirs() }

    override val homeDir: File
        get() = File(filesDir, "home").also { it.mkdirs() }

    override val cacheDir: File
        get() = super.getCacheDir()

    override val packageName: String
        get() = super.getPackageName()

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
