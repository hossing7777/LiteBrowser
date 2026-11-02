package com.litebrowser

import android.content.Context
import android.system.Os
import java.io.File

object NativeInstaller {
    private val FILES = listOf(
        Triple("libpr-cli.so", "bin/pr-cli", "pr-cli"),
        Triple("libbusybox.so", "bin/busybox", "busybox"),
        Triple("libproot.so", "lib/libproot.so", "libproot.so"),
        Triple("libproot-loader.so", "lib/libproot-loader.so", "libproot-loader.so")
    )

    fun nativeDir(c: Context) = File(c.applicationInfo.nativeLibraryDir)

    fun prCli(c: Context): File {
        val native = File(nativeDir(c), "libpr-cli.so")
        if (native.exists()) return native
        val tmp = File("/data/local/tmp/libpr-cli.so")
        if (tmp.exists() && tmp.canExecute()) return tmp
        try {
            val src = File(c.filesDir, "usr/bin/pr-cli")
            if (src.exists()) {
                tmp.parentFile?.mkdirs()
                src.copyTo(tmp, overwrite = true)
                Os.chmod(tmp.absolutePath, 493)
                return tmp
            }
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "prCli copy failed", e)
        }
        return File(c.filesDir, "usr/bin/pr-cli")
    }

    fun install(context: Context) {
        try {
            val prefix = File(context.filesDir, "usr")
            File(prefix, "bin").mkdirs()
            File(prefix, "lib").mkdirs()
            File(context.filesDir, "home").mkdirs()
            File(context.cacheDir, "proot-tmp").mkdirs() // ساخت پوشه امن موقت

            val alpineRoot = File(prefix, "var/lib/pr/installed-rootfs/alpine")
            for (d in listOf("tmp", "proc", "sys", "dev", "etc")) File(alpineRoot, d).mkdirs()
            File(alpineRoot, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            val nativeDir = nativeDir(context)

            for ((libName, rel, assetName) in FILES) {
                val dest = File(prefix, rel)
                val src = File(nativeDir, libName)
                try {
                    if (src.exists()) {
                        if (dest.exists()) dest.delete()
                        src.copyTo(dest, overwrite = true)
                    } else {
                        if (dest.exists()) dest.delete()
                        context.assets.open("native/arm64-v8a/$assetName").use { i ->
                            dest.outputStream().use { o -> i.copyTo(o) }
                        }
                    }
                    Os.chmod(dest.absolutePath, 493)
                    Logger.i("NativeInstaller", "OK ${dest.absolutePath}")
                } catch (e: Exception) {
                    Logger.e("NativeInstaller", "Failed ${dest.name}", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
