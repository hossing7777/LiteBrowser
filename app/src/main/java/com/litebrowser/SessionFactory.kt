package com.litebrowser

import android.content.Context
import com.termux.terminal.TerminalSession
import java.io.File

object SessionFactory {

    const val DOWNLOAD_HOST = "/storage/emulated/0/Download"
    const val CACHE_HOST = "/storage/emulated/0/Download/Local_AI_Models/Library_Cache"

    fun createSession(context: Context, viewWidth: Int, viewHeight: Int): TerminalSession? {
        return try {
            val app = context.applicationContext as LiteApp
            val prefixDir = File(app.filesDir, "usr")
            val homeDir = File(app.filesDir, "home").also { it.mkdirs() }
            val tmpDir = File(app.cacheDir, "proot-tmp").also { it.mkdirs() }
            val nativeDir = app.applicationInfo.nativeLibraryDir
            val resolvConfHost = File(app.filesDir, "etc/resolv.conf")

            val alpineRoot = File(prefixDir, "var/lib/pr/installed-rootfs/alpine")
            if (!alpineRoot.exists() || !File(alpineRoot, "bin/busybox").exists()) {
                Logger.e("SessionFactory", "Alpine rootfs missing at $alpineRoot")
                return null
            }

            File(DOWNLOAD_HOST).mkdirs()
            File(CACHE_HOST).mkdirs()

            val prootBin = File(nativeDir, "libproot.so")
            val prootLoader = File(nativeDir, "libproot-loader.so")

            if (!prootBin.exists()) {
                Logger.e("SessionFactory", "libproot.so not found in $nativeDir")
                return null
            }

            val envVars = arrayOf(
                "PATH=${nativeDir}:${prefixDir.absolutePath}/bin:/system/bin:/system/xbin",
                "HOME=${homeDir.absolutePath}",
                "PREFIX=${prefixDir.absolutePath}",
                "LD_LIBRARY_PATH=${nativeDir}:${prefixDir.absolutePath}/lib",
                "APP_PREFIX=${prefixDir.absolutePath}",
                "APP_HOME=${homeDir.absolutePath}",
                "APP_PACKAGE=${app.packageName}",
                "PROOT_LOADER=${prootLoader.absolutePath}",
                "PROOT_NO_SECCOMP=1",
                "PROOT_TMP_DIR=${tmpDir.absolutePath}",
                "TMPDIR=${tmpDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8"
            )

            val args = arrayOf(
                prootBin.absolutePath, "--link2symlink", "-0",
                "-r", alpineRoot.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "${homeDir.absolutePath}:/root",
                "-b", "${DOWNLOAD_HOST}:/root/Download",
                "-b", "${prefixDir.absolutePath}/bin:/usr/local/bin",
                "-b", "${resolvConfHost.absolutePath}:/etc/resolv.conf",
                "-w", "/root",
                "/bin/sh", "-l"
            )

            val session = TerminalSession(
                prootBin.absolutePath,
                homeDir.absolutePath,
                args,
                envVars,
                2000,
                TerminalManager
            )
            session.updateSize(80.coerceAtLeast(20), 24.coerceAtLeast(8))
            Logger.i("SessionFactory", "Session created pid=${session.getPid()}")
            session
        } catch (e: Throwable) {
            Logger.e("SessionFactory", "create failed", e)
            null
        }
    }
}
