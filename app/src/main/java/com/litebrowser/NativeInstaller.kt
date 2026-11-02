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
                if (dest.exists() && dest.length() > 0) {
                    Logger.i("NativeInstaller", "${dest.name} already installed (${dest.length()} bytes)")
                } else {
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
                }
                // chmod +x صریح
                try {
                    val result = Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath)).waitFor()
                    Logger.i("NativeInstaller", "chmod 755 ${dest.name}: result=$result, canExecute=${dest.canExecute()}")
                } catch (e: Exception) {
                    Logger.e("NativeInstaller", "chmod failed for ${dest.name}", e)
                }
            }

            Logger.i("NativeInstaller", "install complete")
            Logger.i("NativeInstaller", "pr-cli exists: ${File(binDir, "pr-cli").exists()}, canExecute: ${File(binDir, "pr-cli").canExecute()}")
            Logger.i("NativeInstaller", "busybox exists: ${File(binDir, "busybox").exists()}")
            Logger.i("NativeInstaller", "libproot.so exists: ${File(libDir, "libproot.so").exists()}")
            Logger.i("NativeInstaller", "homeDir exists: ${homeDir.exists()}")

        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
