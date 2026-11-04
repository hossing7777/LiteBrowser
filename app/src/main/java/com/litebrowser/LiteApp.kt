package com.litebrowser

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import id.or.oo.pr.engine.ProotHost
import java.io.File

class LiteApp : Application() {

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

        Thread {
            try {
                if (!DistroInstaller.isInstalled(this)) {
                    Logger.i("App", "Alpine not installed yet, installing...")
                    DistroInstaller.install(this) { msg -> Logger.i("App", msg) }
                } else {
                    Logger.i("App", "Alpine already installed")
                }
                // Write DNS config after install
                writeDnsConfig()
            } catch (e: Exception) {
                Logger.e("App", "DistroInstaller failed", e)
            }
        }.start()

        registerNetworkCallback()

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            Logger.crash(thread, ex)
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { writeDnsConfig() }
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { writeDnsConfig() }
            }
            cm.registerDefaultNetworkCallback(networkCallback!!)
            Logger.i("DNS", "Network callback registered")
        } catch (e: Exception) {
            Logger.e("DNS", "Failed to register network callback", e)
        }
    }

    /**
     * Writes DNS servers from Android to a resolv.conf file that can be bind-mounted into Alpine.
     * Also writes a fallback directly into the extracted rootfs (for TerminalActivity without bind).
     */
    fun writeDnsConfig() {
        try {
            val dnsServers = getProotHost().dnsServers
            
            val sb = StringBuilder()
            val ipv4 = dnsServers.filter { !it.contains(":") }
            val ipv6 = dnsServers.filter { it.contains(":") }
            
            if (ipv4.isNotEmpty()) {
                ipv4.forEach { sb.append("nameserver $it\n") }
            }
            if (ipv6.isNotEmpty()) {
                ipv6.take(2).forEach { sb.append("nameserver $it\n") }
            }
            if (sb.isEmpty()) {
                // Fallback
                sb.append("nameserver 8.8.8.8\n")
                sb.append("nameserver 1.1.1.1\n")
                sb.append("nameserver 8.8.4.4\n")
            }
            val content = sb.toString()

            // Primary: for bind mount
            val etcDir = File(filesDir, "etc").also { it.mkdirs() }
            File(etcDir, "resolv.conf").writeText(content)

            // Fallback: write directly into Alpine rootfs (covers TerminalActivity)
            val alpineResolv = File(filesDir, "usr/var/lib/pr/installed-rootfs/alpine/etc/resolv.conf")
            if (alpineResolv.parentFile?.exists() == true) {
                alpineResolv.writeText(content)
            }

            Logger.i("DNS", "Wrote resolv.conf with $dnsServers")
        } catch (e: Exception) {
            Logger.e("DNS", "Failed to write DNS", e)
        }
    }

    fun getProotHost(): ProotHost = object : ProotHost {
        override val prefixDir: File get() = File(filesDir, "usr").also { it.mkdirs() }
        override val homeDir: File get() = File(filesDir, "home").also { it.mkdirs() }
        override val cacheDir: File get() = this@LiteApp.cacheDir
        override val packageName: String get() = this@LiteApp.packageName
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
