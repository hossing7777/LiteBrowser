package com.litebrowser

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

object DistroInstaller {

    private const val DISTRO = "alpine"
    private const val ASSET_NAME = "alpine-rootfs.bin"

    fun isInstalled(context: Context): Boolean {
        val rootfs = File(context.filesDir, "usr/var/lib/pr/installed-rootfs/$DISTRO")
        // ✅ بررسی وجود bin/sh (حتی اگر لینک نمادین باشد)
        val sh = File(rootfs, "bin/sh")
        val busybox = File(rootfs, "bin/busybox")
        return sh.exists() || busybox.exists()
    }

    fun install(context: Context, onProgress: (String) -> Unit = {}) {
        try {
            val prefix = File(context.filesDir, "usr")
            val installed = File(prefix, "var/lib/pr/installed-rootfs/$DISTRO")
            
            if (installed.exists()) {
                installed.deleteRecursively()
            }
            installed.mkdirs()

            onProgress("Extracting Alpine rootfs...")
            Logger.i("DistroInstaller", "Extracting to ${installed.absolutePath}")

            context.assets.open(ASSET_NAME).use { input ->
                GZIPInputStream(input).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        var entry: TarArchiveEntry? = tar.nextEntry
                        var count = 0
                        
                        while (entry != null) {
                            val outFile = File(installed, entry.name)
                            
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                outFile.parentFile?.mkdirs()
                                try {
                                    if (outFile.exists()) outFile.delete()
                                    Os.symlink(entry.linkName, outFile.absolutePath)
                                } catch (e: Exception) {
                                    Logger.e("DistroInstaller", "Symlink failed: ${entry.name} -> ${entry.linkName}", e)
                                }
                            } else if (entry.isLink) {
                                outFile.parentFile?.mkdirs()
                                val target = File(installed, entry.linkName)
                                if (target.exists()) {
                                    target.copyTo(outFile, overwrite = true)
                                }
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    tar.copyTo(out)
                                }
                                if (entry.mode and 0x40 != 0) {
                                    outFile.setExecutable(true)
                                }
                            }
                            count++
                            if (count % 200 == 0) onProgress("Extracted $count entries...")
                            entry = tar.nextEntry
                        }
                    }
                }
            }

            val shFile = File(installed, "bin/sh")
            if (shFile.exists()) {
                Logger.i("DistroInstaller", "✅ Alpine installed successfully.")
                onProgress("Done.")
            } else {
                Logger.e("DistroInstaller", "❌ bin/sh still missing!")
                onProgress("Error: bin/sh not found")
            }
            
        } catch (e: Exception) {
            Logger.e("DistroInstaller", "install failed", e)
            onProgress("Error: ${e.message}")
        }
    }
}
