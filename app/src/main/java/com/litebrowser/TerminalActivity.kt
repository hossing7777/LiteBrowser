package com.litebrowser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null
    private var sessionAttached = false
    private var currentFontSize = 18f
    private var ctrlPressed = false
    private var altPressed = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_terminal)

        terminalView = findViewById(R.id.terminal_view)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.setTerminalViewClient(this)

        setupButtons()

        terminalView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                if (right > left && bottom > top && !sessionAttached) {
                    startTerminalSession(right - left, bottom - top)
                }
            }
        })
        terminalView.requestFocus()
    }

    private fun setupButtons() {
        // ... (کد دکمه‌ها بدون تغییر)
    }

    private fun startTerminalSession(viewWidth: Int, viewHeight: Int) {
        if (sessionAttached) return
        try {
            val app = application as LiteApp
            val prefixDir = File(app.filesDir, "usr")
            val homeDir = File(app.filesDir, "home").also { it.mkdirs() }
            
            // **مهم:** پوشه موقت را از قبل می‌سازیم
            val tmpDir = File(app.cacheDir, "proot-tmp").also { it.mkdirs() }
            val nativeDir = app.applicationInfo.nativeLibraryDir

            // **مهم:** pr-cli را مستقیماً از nativeLibraryDir اجرا می‌کنیم
            val prCli = File(nativeDir, "libpr-cli.so")
            if (!prCli.exists()) throw IllegalStateException("pr-cli not found in nativeLibraryDir")

            val alpineRoot = File(prefixDir, "var/lib/pr/installed-rootfs/alpine")
            if (!File(alpineRoot, "bin/sh").exists()) throw IllegalStateException("Alpine not installed")

            val envVars = arrayOf(
                "PATH=${nativeDir}:/system/bin:/system/xbin",
                "HOME=${homeDir.absolutePath}",
                "PREFIX=${prefixDir.absolutePath}",
                "LD_LIBRARY_PATH=${nativeDir}", // **مهم:** مسیر کتابخانه‌ها
                "APP_PREFIX=${prefixDir.absolutePath}",
                "APP_HOME=${homeDir.absolutePath}",
                "APP_PACKAGE=${app.packageName}",
                "PROOT_LOADER=${nativeDir}/libproot-loader.so", // **مهم:** لودر از nativeLibraryDir
                "PROOT_NO_SECCOMP=1",
                "PROOT_TMP_DIR=${tmpDir.absolutePath}", // **مهم:** پوشه موقت ساخته شده
                "TMPDIR=${tmpDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8"
            )

            terminalView.setTextSize(currentFontSize.toInt())
            val cols = (viewWidth / (currentFontSize * 0.62f)).toInt().coerceIn(20, 200)
            val rows = (viewHeight / (currentFontSize * 1.35f)).toInt().coerceIn(8, 100)

            // **مهم:** argv[0] نام برنامه است
            val session = TerminalSession(prCli.absolutePath, homeDir.absolutePath, arrayOf("pr-cli", "login", "alpine"), envVars, 2000, this)
            if (!session.isRunning) throw IllegalStateException("Terminal failed")
            
            session.updateSize(cols, rows)
            terminalSession = session
            terminalView.attachSession(session)
            sessionAttached = true
            terminalView.requestFocus()
            handler.postDelayed({ showKeyboard() }, 500)
        } catch (e: Throwable) {
            Logger.e("Terminal", "Startup failed", e)
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    // ... (بقیه متدها بدون تغییر)
}
