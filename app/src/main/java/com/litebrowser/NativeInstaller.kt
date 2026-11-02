package com.litebrowser

import android.content.Context
import android.system.Os
import java.io.File

object NativeInstaller {
    fun nativeDir(c: Context) = File(c.applicationInfo.nativeLibraryDir)

    fun install(context: Context) {
        try {
            val prefix = File(context.filesDir, "usr")
            File(prefix, "bin").mkdirs()
            File(prefix, "lib").mkdirs()
            File(context.filesDir, "home").mkdirs()
            // ساخت پوشه موقت امن
            File(context.cacheDir, "proot-tmp").mkdirs()

            val alpineRoot = File(prefix, "var/lib/pr/installed-rootfs/alpine")
            for (d in listOf("tmp", "proc", "sys", "dev", "etc")) File(alpineRoot, d).mkdirs()
            File(alpineRoot, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            // **مهم:** pr-cli و proot دیگر از files اجرا نمی‌شوند، پس نیازی به کپی آنها نیست.
            // فقط فایل‌های کمکی (مثل busybox) را کپی می‌کنیم.
            val nativeDir = nativeDir(context)
            val busyboxDest = File(prefix, "bin/busybox")
            val busyboxSrc = File(nativeDir, "libbusybox.so")
            if (busyboxSrc.exists()) {
                busyboxSrc.copyTo(busyboxDest, overwrite = true)
                Os.chmod(busyboxDest.absolutePath, 493)
            }
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
