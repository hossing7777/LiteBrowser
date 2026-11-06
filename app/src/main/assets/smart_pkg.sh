#!/bin/sh
# smart_pkg.sh - Intelligent Package Installer for Alpine PRoot
# Usage: install_pkg <package_name>

PKG="$1"
if [ -z "$PKG" ]; then
    echo "Usage: install_pkg <package_name>"
    exit 1
fi

# 1. اگر پکیج از قبل نصب است، خارج شو
if command -v "$PKG" >/dev/null 2>&1; then
    echo "[OK] '$PKG' is already installed."
    exit 0
fi

# 2. تلاش برای نصب با apk (مخازن Alpine)
echo "[+] Trying 'apk add $PKG' ..."
apk add --no-cache "$PKG" >/dev/null 2>&1
if [ $? -eq 0 ] && command -v "$PKG" >/dev/null 2>&1; then
    echo "[OK] '$PKG' installed via apk."
    exit 0
fi

# 3. اگر apk نشد، تلاش برای نصب پکیج پایتون با pip3
echo "[+] Trying 'pip3 install $PKG' ..."
pip3 install --break-system-packages "$PKG" >/dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "[OK] '$PKG' installed via pip3."
    exit 0
fi

# 4. نصب نشد
echo "[ERROR] Failed to install '$PKG' via apk or pip3."
exit 1
