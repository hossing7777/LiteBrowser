#!/data/data/com.termux/files/usr/bin/bash
# fix_litebrowser.sh
set -euo pipefail

ROOT="."
cd "$ROOT"

[ -f settings.gradle.kts ] || { echo "ERROR: run in LiteBrowser root"; exit 1; }
[ -f app/build.gradle.kts ] || { echo "ERROR: app/build.gradle.kts not found"; exit 1; }

APP_SRC="app/src/main"
KT_DIR="$APP_SRC/java/com/litebrowser"
JNI="$APP_SRC/jniLibs/arm64-v8a"
ASSET_NATIVE="$APP_SRC/assets/native/arm64-v8a"
ENGINE_JNI="proot-engine/src/main/jniLibs/arm64-v8a"

echo "==> [1/5] Ensure native .so files exist in app jniLibs"
mkdir -p "$JNI"
copy_if_missing() {
  local dest="$JNI/$1"; shift
  [ -f "$dest" ] && return 0
  for c in "$@"; do
    if [ -f "$c" ]; then cp "$c" "$dest"; echo "   copied $c -> $dest"; return 0; fi
  done
  echo "   WARNING: could not find source for $(basename "$dest")"
}
copy_if_missing libpr-cli.so        "$ENGINE_JNI/libpr-cli.so"        "$ASSET_NATIVE/pr-cli"
copy_if_missing libbusybox.so       "$ENGINE_JNI/libbusybox.so"       "$ASSET_NATIVE/busybox"
copy_if_missing libproot.so         "$ENGINE_JNI/libproot.so"         "$ASSET_NATIVE/libproot.so"
copy_if_missing libproot-loader.so  "$ENGINE_JNI/libproot-loader.so"  "$ASSET_NATIVE/libproot-loader.so"
ls -la "$JNI" || true

echo "==> [2/5] Patch app/build.gradle.kts (legacy packaging = extract .so)"
python3 - <<'PY'
import re
p = "app/build.gradle.kts"
s = open(p, encoding="utf-8").read()
if "useLegacyPackaging" not in s:
    block = '''
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "**/libpr-cli.so", "**/libbusybox.so",
                "**/libproot.so", "**/libproot-loader.so"
            )
        }
    }
'''
    s = s.replace('namespace = "com.litebrowser"\n    compileSdk = 34\n',
                  'namespace = "com.litebrowser"\n    compileSdk = 34\n' + block, 1)
    if "useLegacyPackaging" not in s:
        s = re.sub(r'(android\s*\{)', r'\1' + block, s, 1)
    open(p, "w", encoding="utf-8").write(s)
    print("   build.gradle.kts patched")
else:
    print("   already patched")
PY

echo "==> [3/5] Patch AndroidManifest (extractNativeLibs=true)"
python3 - <<'PY'
p = "app/src/main/AndroidManifest.xml"
s = open(p, encoding="utf-8").read()
if "extractNativeLibs" not in s:
    s = s.replace('android:name=".LiteApp"',
                  'android:name=".LiteApp"\n        android:extractNativeLibs="true"', 1)
    open(p, "w", encoding="utf-8").write(s)
    print("   manifest patched")
else:
    print("   already patched")
PY

echo "==> [4/5] Rewrite NativeInstaller.kt"
cat > "$KT_DIR/NativeInstaller.kt" <<'EOF'
package com.litebrowser

import android.content.Context
import android.system.Os
import java.io.File

object NativeInstaller {
    private val FILES = listOf(
        Triple("libpr-cli.so", "bin/pr-cli", "pr-cli"),
        Triple("libbusybox.so", "bin/busybox", "busybox"),
        Triple("libproot.so", "lib/libproot.so", "libproot.so"),
        Triple("libproot-loader.so", "lib/libproot-loader.so", "libproot-loader.so")
    )

    fun nativeDir(c: Context) = File(c.applicationInfo.nativeLibraryDir)

    fun prCli(c: Context): File {
        val n = File(nativeDir(c), "libpr-cli.so")
        return if (n.exists()) n else File(c.filesDir, "usr/bin/pr-cli")
    }

    fun install(context: Context) {
        try {
            val prefix = File(context.filesDir, "usr")
            File(prefix, "bin").mkdirs()
            File(prefix, "lib").mkdirs()
            File(context.filesDir, "home").mkdirs()

            val alpineRoot = File(prefix, "installed/alpine")
            for (d in listOf("tmp", "proc", "sys", "dev", "etc")) File(alpineRoot, d).mkdirs()
            File(alpineRoot, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            val nativeDir = nativeDir(context)
            Logger.i("NativeInstaller", "nativeLibraryDir=${nativeDir.absolutePath}")
            Logger.i("NativeInstaller", "contents=${nativeDir.list()?.joinToString(",")}")

            for ((libName, rel, assetName) in FILES) {
                val dest = File(prefix, rel)
                val src = File(nativeDir, libName)
                try {
                    if (src.exists()) {
                        if (!dest.exists() || dest.length() != src.length()) src.copyTo(dest, overwrite = true)
                    } else {
                        Logger.w("NativeInstaller", "not in nativeDir: $libName, extracting from assets")
                        context.assets.open("native/arm64-v8a/$assetName").use { i ->
                            dest.outputStream().use { o -> i.copyTo(o) }
                        }
                    }
                    Os.chmod(dest.absolutePath, 493)
                    Logger.i("NativeInstaller", "OK ${dest.absolutePath} size=${dest.length()}")
                } catch (e: Exception) {
                    Logger.e("NativeInstaller", "Failed ${dest.name}", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("NativeInstaller", "install failed", e)
        }
    }
}
EOF

echo "==> [5/5] Patch TerminalActivity.kt"
python3 - <<'PY'
p = "app/src/main/java/com/litebrowser/TerminalActivity.kt"
s = open(p, encoding="utf-8").read()
s = s.replace('val prCli = File(prefixDir, "bin/pr-cli")',
              'val prCli = NativeInstaller.prCli(app)\n            val nativeDir = app.applicationInfo.nativeLibraryDir\n            Logger.i("TerminalActivity", "prCli=${prCli.absolutePath} exists=${prCli.exists()} exec=${prCli.canExecute()}")')
if "PROOT_LOADER" not in s:
    s = s.replace('"LD_LIBRARY_PATH=${libDir.absolutePath}",',
                  '"LD_LIBRARY_PATH=${libDir.absolutePath}:$nativeDir",\n                "PROOT_LOADER=$nativeDir/libproot-loader.so",')
open(p, "w", encoding="utf-8").write(s)
print("   TerminalActivity patched")
PY

echo
echo "DONE."
