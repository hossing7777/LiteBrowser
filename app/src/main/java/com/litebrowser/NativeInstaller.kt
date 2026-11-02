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
            
            val tmpDir = File(context.cacheDir, "proot-tmp")
            tmpDir.mkdirs()
            tmpDir.setReadable(true, false)
            tmpDir.setWritable(true, false)
            tmpDir.setExecutable(true, false)

            val alpineRoot = File(prefix, "var/lib/pr/installed-rootfs/alpine")
            // ✅ ایجاد تمام دایرکتوری‌های ضروری از جمله dev/shm
            for (d in listOf("tmp", "proc", "sys", "dev", "dev/pts", "dev/shm", "etc")) {
                File(alpineRoot, d).mkdirs()
            }
            
            File(alpineRoot, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            val nativeDir = nativeDir(context)
            
            val busyboxDest = File(prefix, "bin/busybox")
            val busyboxSrc = File(nativeDir, "libbusybox.so")
            if (busyboxSrc.exists()) {
                busyboxSrc.copyTo(busyboxDest, overwrite = true)
                Os.chmod(busyboxDest.absolutePath, 493)
            }

            val tallocDest = File(prefix, "lib/libtalloc.so.2")
            val tallocSrc = File(nativeDir, "libtalloc.so")
            if (tallocSrc.exists()) {
                tallocSrc.copyTo(tallocDest, overwrite = true)
                Os.chmod(tallocDest.absolutePath, 493)
            }
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
