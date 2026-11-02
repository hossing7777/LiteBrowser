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
        // ۱. اول nativeLibraryDir رو چک کن
        val native = File(nativeDir(c), "libpr-cli.so")
        if (native.exists()) {
            Logger.i("NativeInstaller", "prCli: using nativeLibraryDir: ${native.absolutePath}")
            return native
        }
        // ۲. بعد /data/local/tmp رو چک کن (اگر دسترسی داریم)
        val tmp = File("/data/local/tmp/libpr-cli.so")
        if (tmp.exists() && tmp.canExecute()) {
            Logger.i("NativeInstaller", "prCli: using /data/local/tmp: ${tmp.absolutePath}")
            return tmp
        }
        // ۳. در نهایت، کپی به /data/local/tmp
        try {
            val src = File(c.filesDir, "usr/bin/pr-cli")
            if (src.exists()) {
                tmp.parentFile?.mkdirs()
                src.copyTo(tmp, overwrite = true)
                Os.chmod(tmp.absolutePath, 493) // 0755
                Logger.i("NativeInstaller", "prCli: copied to /data/local/tmp: ${tmp.absolutePath}, canExecute=${tmp.canExecute()}")
                return tmp
            }
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "prCli: failed to copy to /data/local/tmp", e)
        }
        return File(c.filesDir, "usr/bin/pr-cli")
    }

    fun install(context: Context) {
        try {
            val prefix = File(context.filesDir, "usr")
            File(prefix, "bin").mkdirs()
            File(prefix, "lib").mkdirs()
            File(context.filesDir, "home").mkdirs()

            val alpineRoot = File(prefix, "var/lib/pr/installed-rootfs/alpine")
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
                        if (dest.exists()) dest.delete()
                    Os.symlink(src.absolutePath, dest.absolutePath)
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
