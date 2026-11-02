package com.litebrowser

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

object NativeInstaller {

    private const val ABI = "arm64-v8a"

    fun install(context: Context) {
        try {
            val prefix = File(context.filesDir, "usr")
            val binDir = File(prefix, "bin")
            val libDir = File(prefix, "lib")
            val homeDir = File(context.filesDir, "home")
            binDir.mkdirs()
            libDir.mkdirs()
            homeDir.mkdirs()

            Logger.i("NativeInstaller", "binDir: ${binDir.absolutePath}, exists: ${binDir.exists()}")
            Logger.i("NativeInstaller", "homeDir: ${homeDir.absolutePath}, exists: ${homeDir.exists()}")

            val files = listOf(
                "native/$ABI/pr-cli" to File(binDir, "pr-cli"),
                "native/$ABI/busybox" to File(binDir, "busybox"),
                "native/$ABI/libproot.so" to File(libDir, "libproot.so"),
                "native/$ABI/libproot-loader.so" to File(libDir, "libproot-loader.so")
            )

            for ((assetPath, dest) in files) {
                if (!dest.exists() || dest.length() == 0L) {
                    try {
                        Logger.i("NativeInstaller", "Copying $assetPath → ${dest.absolutePath}")
                        context.assets.open(assetPath).use { input ->
                            FileOutputStream(dest).use { out ->
                                input.copyTo(out)
                            }
                        }
                        Logger.i("NativeInstaller", "${dest.name} installed (${dest.length()} bytes)")
                    } catch (e: Exception) {
                        Logger.e("NativeInstaller", "Failed to install ${dest.name}: ${e.message}", e)
                        continue
                    }
                } else {
                    Logger.i("NativeInstaller", "${dest.name} already installed (${dest.length()} bytes)")
                }

                // 🔑 chmod با Os.chmod (NDK-level) - کار می‌کنه روی Android 10+
                try {
                    val mode = OsConstants.S_IRWXU or OsConstants.S_IRGRP or OsConstants.S_IXGRP or OsConstants.S_IROTH or OsConstants.S_IXOTH // 0755
                    Os.chmod(dest.absolutePath, mode)
                    Logger.i("NativeInstaller", "Os.chmod 0755 ${dest.name}: canExecute=${dest.canExecute()}, canRead=${dest.canRead()}")
                } catch (e: Exception) {
                    Logger.e("NativeInstaller", "Os.chmod failed for ${dest.name}", e)
                }
            }

            // ساخت پوشه‌های لازم برای Alpine
            val alpineRoot = File(prefix, "installed/alpine")
            File(alpineRoot, "tmp").mkdirs()
            File(alpineRoot, "proc").mkdirs()
            File(alpineRoot, "sys").mkdirs()
            File(alpineRoot, "dev").mkdirs()

            // ساخت /etc/resolv.conf برای DNS
            try {
                val resolv = File(alpineRoot, "etc/resolv.conf")
                resolv.parentFile?.mkdirs()
                resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                Logger.i("NativeInstaller", "resolv.conf created")
            } catch (e: Exception) {
                Logger.e("NativeInstaller", "resolv.conf failed", e)
            }

            Logger.i("NativeInstaller", "install complete")
            Logger.i("NativeInstaller", "pr-cli canExecute: ${File(binDir, "pr-cli").canExecute()}")
            Logger.i("NativeInstaller", "busybox canExecute: ${File(binDir, "busybox").canExecute()}")
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
