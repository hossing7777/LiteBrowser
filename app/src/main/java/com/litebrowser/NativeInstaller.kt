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

            val nativeDirFile = nativeDir(context)
            
            val busyboxDest = File(prefix, "bin/busybox")
            val busyboxSrc = File(nativeDirFile, "libbusybox.so")
            if (busyboxSrc.exists()) {
                busyboxSrc.copyTo(busyboxDest, overwrite = true)
                Os.chmod(busyboxDest.absolutePath, 493)
            }

            val tallocDest = File(prefix, "lib/libtalloc.so.2")
            val tallocSrc = File(nativeDirFile, "libtalloc.so")
            if (tallocSrc.exists()) {
                tallocSrc.copyTo(tallocDest, overwrite = true)
                Os.chmod(tallocDest.absolutePath, 493)
            }

            // ✅ ساخت اسکریپت هوشمند نصب پکیج در زمان اجرا
            val smartPkgScript = File(prefix, "bin/install_pkg")
            smartPkgScript.writeText("""
                #!/bin/sh
                PKG="${'$'}1"
                if [ -z "${'$'}PKG" ]; then echo "Usage: install_pkg <package>"; exit 1; fi
                if command -v "${'$'}PKG" >/dev/null 2>&1; then echo "[OK] '${'$'}PKG' is already installed."; exit 0; fi
                echo "[+] Trying 'apk add ${'$'}PKG' ..."
                apk add --no-cache "${'$'}PKG" >/dev/null 2>&1
                if [ ${'$'}? -eq 0 ] && command -v "${'$'}PKG" >/dev/null 2>&1; then echo "[OK] '${'$'}PKG' installed via apk."; exit 0; fi
                echo "[+] Trying 'pip3 install ${'$'}PKG' ..."
                pip3 install --break-system-packages "${'$'}PKG" >/dev/null 2>&1
                if [ ${'$'}? -eq 0 ]; then echo "[OK] '${'$'}PKG' installed via pip3."; exit 0; fi
                echo "[ERROR] Failed to install '${'$'}PKG'."; exit 1
            """.trimIndent())
            Os.chmod(smartPkgScript.absolutePath, 493)

            val askScript = File(prefix, "bin/ask")
            askScript.writeText("""
                #!/bin/sh
                if [ -z "${'$'}1" ]; then echo "Usage: ask <prompt>"; exit 1; fi
                REQ_FILE="/root/ai_req.txt"
                RES_FILE="/root/ai_res.txt"
                echo -n "${'$'}*" | base64 | tr -d '\n' > "${'$'}REQ_FILE"
                echo -e "\033[33mWaiting for AI...\033[0m" >&2
                count=0
                while [ ! -f "${'$'}RES_FILE" ]; do
                    sleep 0.5; count=${'$'}((count+1))
                    if [ ${'$'}count -ge 600 ]; then
                        echo -e "\033[31mError: Timeout\033[0m" >&2; rm -f "${'$'}REQ_FILE"; exit 1
                    fi
                done
                echo -e "\033[32mAI:\033[0m"
                base64 -d "${'$'}RES_FILE"; echo ""
                rm -f "${'$'}REQ_FILE" "${'$'}RES_FILE"
            """.trimIndent())
            Os.chmod(askScript.absolutePath, 493)

        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
