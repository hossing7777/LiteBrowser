package com.litebrowser

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

/**
 * استخراج rootfs از assets به prefixDir/installed/alpine/
 */
object DistroInstaller {

    private const val DISTRO = "alpine"
    private const val ASSET_NAME = "alpine-minirootfs-3.20.3-aarch64.tar.gz"

    fun isInstalled(context: Context): Boolean {
        val rootfs = File(context.filesDir, "usr/installed/$DISTRO")
        return File(rootfs, "bin/sh").exists()
    }

    fun install(context: Context, onProgress: (String) -> Unit = {}) {
        try {
            val prefix = File(context.filesDir, "usr")
            val installed = File(prefix, "installed/$DISTRO")
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
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    tar.copyTo(out)
                                }
                                outFile.setExecutable(entry.mode and 0x49 != 0) // -rwx
                                if (entry.name.startsWith("bin/") || entry.name.startsWith("sbin/") || entry.name.startsWith("usr/bin/") || entry.name.startsWith("usr/sbin/")) {
                                    outFile.setExecutable(true)
                                }
                            }
                            count++
                            if (count % 100 == 0) onProgress("Extracted $count files...")
                            tar.closeEntry()
                            entry = tar.nextEntry
                        }
                    }
                }
            }

            onProgress("Done. $DISTRO installed.")
            Logger.i("DistroInstaller", "Alpine installed at ${installed.absolutePath}")
        } catch (e: Exception) {
            Logger.e("DistroInstaller", "install failed", e)
            onProgress("Error: ${e.message}")
        }
    }
}
