package com.litebrowser

import android.content.Context
import android.system.Os
import java.io.File

object NativeInstaller {
    fun nativeDir(c: Context) = File(c.applicationInfo.nativeLibraryDir)

    fun install(context: Context) {
        try {
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
            
            val busyboxDest = File(prefix, "bin/busybox")
            val busyboxSrc = File(nativeDir, "libbusybox.so")
            if (busyboxSrc.exists()) {
                busyboxSrc.copyTo(busyboxDest, overwrite = true)
                Os.chmod(busyboxDest.absolutePath, 493)
            }

            val tallocDest = File(prefix, "lib/libtalloc.so.2")
            val tallocSrc = File(nativeDir, "libtalloc.so")
            if (tallocSrc.exists()) {
                tallocSrc.copyTo(tallocDest, overwrite = true)
                Os.chmod(tallocDest.absolutePath, 493)
            }

            // ✅ ساخت اسکریپت ask برای ارتباط با هوش مصنوعی
            val askScript = File(prefix, "bin/ask")
            askScript.writeText("""
                #!/bin/sh
                if [ -z "${'$'}1" ]; then
                    echo "Usage: ask <prompt>"
                    exit 1
                fi
                REQ_FILE="/root/ai_req.txt"
                RES_FILE="/root/ai_res.txt"
                echo -n "${'$'}*" > "${'$'}REQ_FILE"
                echo -e "\033[33mWaiting for AI... (Switch to browser if it's not open)\033[0m" >&2
                while [ ! -f "${'$'}RES_FILE" ]; do
                    sleep 0.5
                done
                echo -e "\033[32mAI:\033[0m"
                cat "${'$'}RES_FILE"
                echo ""
                rm -f "${'$'}REQ_FILE" "${'$'}RES_FILE"
            """.trimIndent())
            Os.chmod(askScript.absolutePath, 493)

        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
