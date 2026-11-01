package com.litebrowser

import android.content.Context
import java.io.File

object NativeInstaller {
    fun install(context: Context) {
        try {
            val prefix = File(context.filesDir, "usr")
            val binDir = File(prefix, "bin")
            val libDir = File(prefix, "lib")
            binDir.mkdirs()
            libDir.mkdirs()

            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            Logger.i("NativeInstaller", "nativeLibDir = $nativeLibDir")

            // pr-cli
            val prCliSrc = File(nativeLibDir, "libpr-cli.so")
            val prCliDst = File(binDir, "pr-cli")
            if (prCliSrc.exists()) {
                prCliSrc.copyTo(prCliDst, overwrite = true)
                prCliDst.setExecutable(true)
                Logger.i("NativeInstaller", "pr-cli installed")
            } else {
                Logger.e("NativeInstaller", "pr-cli not found")
            }

            // busybox
            val busyboxSrc = File(nativeLibDir, "libbusybox.so")
            val busyboxDst = File(binDir, "busybox")
            if (busyboxSrc.exists()) {
                busyboxSrc.copyTo(busyboxDst, overwrite = true)
                busyboxDst.setExecutable(true)
                Logger.i("NativeInstaller", "busybox installed")
            }

            // proot و loader به lib/
            listOf("libproot.so", "libproot-loader.so").forEach { name ->
                val s = File(nativeLibDir, name)
                val d = File(libDir, name)
                if (s.exists()) {
                    s.copyTo(d, overwrite = true)
                    d.setExecutable(true)
                    Logger.i("NativeInstaller", "$name installed")
                }
            }

            Logger.i("NativeInstaller", "install complete")
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
