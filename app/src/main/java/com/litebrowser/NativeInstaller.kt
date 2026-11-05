package com.litebrowser

import android.content.Context
import android.system.Os
import java.io.File

object NativeInstaller {

    private data class NativeCopy(val libName: String, val destRel: String)

    private val FILES = listOf(
        NativeCopy("libbusybox.so", "bin/busybox"),
        NativeCopy("libpr-cli.so", "bin/pr-cli"),
        NativeCopy("libproot.so", "lib/libproot.so"),
        NativeCopy("libproot-loader.so", "lib/libproot-loader.so"),
        NativeCopy("libtalloc.so", "lib/libtalloc.so.2")
    )

    fun nativeDir(c: Context) = File(c.applicationInfo.nativeLibraryDir)

    fun prootBin(c: Context): File {
        val n = File(nativeDir(c), "libproot.so")
        return if (n.exists()) n else File(c.filesDir, "usr/lib/libproot.so")
    }

    fun prootLoader(c: Context): File {
        val n = File(nativeDir(c), "libproot-loader.so")
        return if (n.exists()) n else File(c.filesDir, "usr/lib/libproot-loader.so")
    }

    private fun dumpAndroidProps(context: Context) {
        try {
            val propsFile = File(context.filesDir, "home/Android_Props.txt")
            val sb = StringBuilder()
            val props = listOf(
                "ro.product.brand", "ro.product.manufacturer", "ro.product.model",
                "ro.product.device", "ro.product.name", "ro.build.fingerprint",
                "ro.build.id", "ro.build.type", "ro.build.tags",
                "ro.build.version.release", "ro.build.version.sdk",
                "ro.build.version.security_patch", "ro.bootloader",
                "gsm.version.baseband", "ro.serialno", "ro.build.date",
                "ro.hardware", "ro.board.platform", "ro.soc.manufacturer",
                "ro.soc.model", "ro.opengles.version", "ro.hardware.egl",
                "ro.hardware.vulkan"
            )
            for (prop in props) {
                val value = try {
                    val p = Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", prop))
                    p.inputStream.bufferedReader().readText().trim().ifEmpty { "N/A" }
                } catch (e: Exception) { "N/A" }
                sb.append(prop).append("=").append(value).append("\n")
            }
            propsFile.writeText(sb.toString())
            Logger.i("NativeInstaller", "Android props dumped to " + propsFile.absolutePath)
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "Failed to dump Android props", e)
        }
    }

    fun install(context: Context) {
        try {
            if (PrivilegeManager.isShizukuAvailable()) {
                PrivilegeManager.grantAllPermissions(context.packageName)
            } else {
                Logger.w("NativeInstaller", "Shizuku not available, falling back to manual grants")
            }
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
            for (d in listOf("tmp", "proc", "sys", "dev", "etc", "dev/pts", "dev/shm")) {
                File(alpineRoot, d).mkdirs()
            }
            File(alpineRoot, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            val nativeDir = nativeDir(context)
            Logger.i("NativeInstaller", "nativeLibraryDir=${nativeDir.absolutePath}")
            Logger.i("NativeInstaller", "contents=${nativeDir.list()?.joinToString(",")}")

            for (copy in FILES) {
                val dest = File(prefix, copy.destRel)
                val src = File(nativeDir, copy.libName)
                try {
                    if (src.exists()) {
                        if (!dest.exists() || dest.length() != src.length()) {
                            dest.parentFile?.mkdirs()
                            src.copyTo(dest, overwrite = true)
                        }
                        Os.chmod(dest.absolutePath, 493)
                        Logger.i("NativeInstaller", "OK ${dest.absolutePath} size=${dest.length()}")
                    } else {
                        Logger.w("NativeInstaller", "missing in nativeDir: ${copy.libName}")
                    }
                } catch (e: Exception) {
                    Logger.e("NativeInstaller", "Failed ${copy.libName}", e)
                }
            }

            val askScript = File(prefix, "bin/ask")
            askScript.writeText("""
                #!/bin/sh
                if [ -z "${'$'}1" ]; then
                    echo "Usage: ask <prompt>"
                    exit 1
                fi
                REQ_FILE="/root/ai_req.txt"
                RES_FILE="/root/ai_res.txt"
                rm -f "${'$'}RES_FILE"
                echo -n "${'$'}*" | base64 | tr -d '\n' > "${'$'}REQ_FILE"
                echo -e "\033[33mWaiting for AI... (Switch to browser if it is not open)\033[0m" >&2
                count=0
                while [ ! -f "${'$'}RES_FILE" ]; do
                    sleep 0.5
                    count=${'$'}((count+1))
                    if [ ${'$'}count -ge 600 ]; then
                        echo -e "\033[31mError: Timeout waiting for AI response\033[0m" >&2
                        rm -f "${'$'}REQ_FILE"
                        exit 1
                    fi
                done
                echo -e "\033[32mAI:\033[0m"
                base64 -d "${'$'}RES_FILE"
                echo ""
                rm -f "${'$'}REQ_FILE" "${'$'}RES_FILE"
            """.trimIndent())
            Os.chmod(askScript.absolutePath, 493)
            Logger.i("NativeInstaller", "ask script installed")
            dumpAndroidProps(context)

            for (lib in listOf("libpr-cli.so", "libproot.so", "libproot-loader.so")) {
                val src = File(nativeDir, lib)
                if (src.exists()) {
                    val simpleName = lib.removePrefix("lib").removeSuffix(".so")
                    val link = File(prefix, "bin/$simpleName")
                    try {
                        if (!link.exists()) Os.symlink(src.absolutePath, link.absolutePath)
                    } catch (_: Exception) {}
                }
            }

        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
