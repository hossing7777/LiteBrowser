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
        val n = File(nativeDir(c), "libpr-cli.so")
        return if (n.exists()) n else File(c.filesDir, "usr/bin/pr-cli")
    }

    fun install(context: Context) {
        try {
            val prefix = File(context.filesDir, "usr")
            File(prefix, "bin").mkdirs()
            File(prefix, "lib").mkdirs()
            File(context.filesDir, "home").mkdirs()

            val alpineRoot = File(prefix, "installed/alpine")
            for (d in listOf("tmp", "proc", "sys", "dev", "etc")) File(alpineRoot, d).mkdirs()
            File(alpineRoot, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            val nativeDir = nativeDir(context)
            Logger.i("NativeInstaller", "nativeLibraryDir=${nativeDir.absolutePath}")
            Logger.i("NativeInstaller", "contents=${nativeDir.list()?.joinToString(",")}")

            for ((libName, rel, assetName) in FILES) {
                val dest = File(prefix, rel)
                val src = File(nativeDir, libName)
                try {
                    if (src.exists()) {
                        if (!dest.exists() || dest.length() != src.length()) src.copyTo(dest, overwrite = true)
                    } else {
                        Logger.w("NativeInstaller", "not in nativeDir: $libName, extracting from assets")
                        context.assets.open("native/arm64-v8a/$assetName").use { i ->
                            dest.outputStream().use { o -> i.copyTo(o) }
                        }
                    }
                    Os.chmod(dest.absolutePath, 493)
                    Logger.i("NativeInstaller", "OK ${dest.absolutePath} size=${dest.length()}")
                } catch (e: Exception) {
                    Logger.e("NativeInstaller", "Failed ${dest.name}", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
