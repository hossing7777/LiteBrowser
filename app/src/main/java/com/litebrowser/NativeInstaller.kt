package com.litebrowser

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object NativeInstaller {

    private const val ABI = "arm64-v8a"

    fun install(context: Context) {
        try {
            val prefix = File(context.filesDir, "usr")
            val binDir = File(prefix, "bin")
            val libDir = File(prefix, "lib")
            binDir.mkdirs()
            libDir.mkdirs()

            // لیست فایل‌ها: (نام در assets, مقصد)
            val files = listOf(
                "native/$ABI/pr-cli" to File(binDir, "pr-cli"),
                "native/$ABI/busybox" to File(binDir, "busybox"),
                "native/$ABI/libproot.so" to File(libDir, "libproot.so"),
                "native/$ABI/libproot-loader.so" to File(libDir, "libproot-loader.so")
            )

            for ((assetPath, dest) in files) {
                if (dest.exists() && dest.length() > 0) {
                    Logger.i("NativeInstaller", "${dest.name} already installed")
                    continue
                }
                try {
                    context.assets.open(assetPath).use { input ->
                        FileOutputStream(dest).use { out ->
                            input.copyTo(out)
                        }
                    }
                    dest.setExecutable(true)
                    dest.setReadable(true)
                    Logger.i("NativeInstaller", "${dest.name} installed (${dest.length()} bytes)")
                } catch (e: Exception) {
                    Logger.e("NativeInstaller", "Failed to install ${dest.name}", e)
                }
            }

            Logger.i("NativeInstaller", "install complete")
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
