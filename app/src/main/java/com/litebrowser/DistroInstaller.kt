package com.litebrowser

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

object DistroInstaller {

    private const val DISTRO = "alpine"
    private const val ASSET_NAME = "alpine-minirootfs-3.20.3-aarch64.tar.gz"

    fun isInstalled(context: Context): Boolean {
        val rootfs = File(context.filesDir, "usr/installed/$DISTRO")
        val sh = File(rootfs, "bin/sh")
        Logger.i("DistroInstaller", "isInstalled check: $rootfs, sh exists: ${sh.exists()}")
        return sh.exists()
    }

    fun install(context: Context, onProgress: (String) -> Unit = {}) {
        try {
            val prefix = File(context.filesDir, "usr")
            val installed = File(prefix, "installed/$DISTRO")
            installed.mkdirs()

            onProgress("Extracting Alpine rootfs from $ASSET_NAME...")
            Logger.i("DistroInstaller", "Extracting $ASSET_NAME to ${installed.absolutePath}")

            context.assets.open(ASSET_NAME).use { input ->
                GZIPInputStream(input).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        var entry: TarArchiveEntry? = tar.nextEntry
                        var count = 0
                        var filesCount = 0
                        
                        while (entry != null) {
                            val outFile = File(installed, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    tar.copyTo(out)
                                }
                                filesCount++
                                
                                if (entry.mode and 0x40 != 0) { // -rwx
                                    outFile.setExecutable(true)
                                }
                                if (entry.name.startsWith("bin/") || entry.name.startsWith("sbin/") || 
                                    entry.name.startsWith("usr/bin/") || entry.name.startsWith("usr/sbin/") ||
                                    entry.name.startsWith("usr/lib/") || entry.name.startsWith("lib/")) {
                                    outFile.setExecutable(true)
                                }
                            }
                            count++
                            if (count % 200 == 0) {
                                onProgress("Extracted $count entries ($filesCount files)...")
                            }
                            entry = tar.nextEntry
                        }
                        
                        Logger.i("DistroInstaller", "Extracted $count entries, $filesCount files total")
                    }
                }
            }

            // چک نهایی
            val shFile = File(installed, "bin/sh")
            if (shFile.exists()) {
                shFile.setExecutable(true)
                Logger.i("DistroInstaller", "✅ Alpine installed successfully at ${installed.absolutePath}")
                onProgress("Done. Alpine installed.")
            } else {
                Logger.e("DistroInstaller", "❌ bin/sh not found after extraction!")
                onProgress("Error: bin/sh not found")
            }
            
        } catch (e: Exception) {
            Logger.e("DistroInstaller", "install failed", e)
            onProgress("Error: ${e.message}")
        }
    }
}
