package com.litebrowser

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

object NativeInstaller {

    fun install(context: Context) {
        try {
            val prefix = File(context.filesDir, "usr")
            val binDir = File(prefix, "bin")
            val libDir = File(prefix, "lib")
            val homeDir = File(context.filesDir, "home")
            binDir.mkdirs()
            libDir.mkdirs()
            homeDir.mkdirs()

            // پوشه‌های لازم برای Alpine
            val alpineRoot = File(prefix, "installed/alpine")
            File(alpineRoot, "tmp").mkdirs()
            File(alpineRoot, "proc").mkdirs()
            File(alpineRoot, "sys").mkdirs()
            File(alpineRoot, "dev").mkdirs()
            File(alpineRoot, "etc").mkdirs()
            File(alpineRoot, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            // کپی از nativeLibraryDir (که اندروید خودش مجوز اجرا داده)
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            Logger.i("NativeInstaller", "nativeLibDir: ${nativeLibDir.absolutePath}")

            val files = mapOf(
                "libpr-cli.so" to File(binDir, "pr-cli"),
                "libbusybox.so" to File(binDir, "busybox"),
                "libproot.so" to File(libDir, "libproot.so"),
                "libproot-loader.so" to File(libDir, "libproot-loader.so")
            )

            for ((srcName, dest) in files) {
                val src = File(nativeLibDir, srcName)
                if (!src.exists()) {
                    Logger.e("NativeInstaller", "Source not found: ${src.absolutePath}")
                    continue
                }
                try {
                    if (!dest.exists() || dest.length() != src.length()) {
                        src.copyTo(dest, overwrite = true)
                        Logger.i("NativeInstaller", "${dest.name} copied (${dest.length()} bytes)")
                    } else {
                        Logger.i("NativeInstaller", "${dest.name} already exists")
                    }
                    // تنظیم مجوز اجرا با Os.chmod
                    val mode = OsConstants.S_IRWXU or OsConstants.S_IRGRP or OsConstants.S_IXGRP or OsConstants.S_IROTH or OsConstants.S_IXOTH
                    Os.chmod(dest.absolutePath, mode)
                    Logger.i("NativeInstaller", "Os.chmod 0755 ${dest.name}: canExecute=${dest.canExecute()}")
                } catch (e: Exception) {
                    Logger.e("NativeInstaller", "Failed to install ${dest.name}", e)
                }
            }

            Logger.i("NativeInstaller", "install complete")
            Logger.i("NativeInstaller", "pr-cli canExecute: ${File(binDir, "pr-cli").canExecute()}")
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
