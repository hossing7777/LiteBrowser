package com.litebrowser

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null
    private var sessionAttached = false
    private lateinit var homeDir: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("TerminalActivity", "onCreate()")

        try {
            setContentView(R.layout.activity_terminal)

            terminalView = findViewById(R.id.terminal_view)
            terminalView.setTerminalViewClient(this)

            findViewById<Button>(R.id.btn_back).setOnClickListener {
                finish()
            }

            findViewById<Button>(R.id.btn_keyboard).setOnClickListener {
                showKeyboard()
            }

            terminalView.setOnClickListener { showKeyboard() }

            terminalView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    if ((right - left) > 0 && (bottom - top) > 0 && !sessionAttached) {
                        sessionAttached = true
                        v.removeOnLayoutChangeListener(this)
                        startTerminalSession(right - left, bottom - top)
                    }
                }
            })

        } catch (e: Throwable) {
            Logger.e("TerminalActivity", "onCreate failed", e)
            throw e
        }
    }

    private fun getStorageRoot(): String {
        // تلاش برای پیدا کردن مسیر storage که قابل دسترسه
        val candidates = listOf(
            "/storage/emulated/0",
            "/sdcard",
            Environment.getExternalStorageDirectory().absolutePath
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists() && f.canRead() && f.canWrite()) {
                Logger.i("TerminalActivity", "Using storage: $path (canRead=${f.canRead()}, canWrite=${f.canWrite()})")
                return path
            } else {
                Logger.w("TerminalActivity", "Cannot use: $path (canRead=${f.canRead()}, canWrite=${f.canWrite()})")
            }
        }
        // اگر هیچکدام نشد، فایل‌های اپ
        return filesDir.absolutePath
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showKeyboard() {
        try {
            terminalView.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
            Logger.e("TerminalActivity", "showKeyboard failed", e)
        }
    }

    private fun startTerminalSession(viewWidth: Int, viewHeight: Int) {
        Logger.i("TerminalActivity", "startTerminalSession: ${viewWidth}x${viewHeight}")
        try {
            val hasStorage = checkStoragePermission()
            Logger.i("TerminalActivity", "hasStorage permission: $hasStorage")

            if (hasStorage) {
                homeDir = getStorageRoot()
            } else {
                homeDir = filesDir.absolutePath
                Logger.w("TerminalActivity", "No storage permission! Using: $homeDir")
            }

            val envVars = arrayOf(
                "PATH=/system/bin:/system/xbin:/system/bin",
                "HOME=$homeDir",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "PWD=$homeDir"
            )

            terminalView.setTextSize(30)

            val session = TerminalSession(
                "/system/bin/sh",
                homeDir,
                arrayOf("-l"),
                envVars,
                2000,
                this
            )
            Logger.i("TerminalActivity", "Session created, running=${session.isRunning}, cwd=$homeDir")

            val cols = (viewWidth / 25).coerceAtLeast(20)
            val rows = (viewHeight / 50).coerceAtLeast(10)
            session.updateSize(cols, rows)

            if (session.isRunning) {
                terminalSession = session
                terminalView.attachSession(session)

                Handler(Looper.getMainLooper()).postDelayed({ showKeyboard() }, 300)

                // نمایش پیام خوش‌آمد + رفتن به storage
                Handler(Looper.getMainLooper()).postDelayed({
                    val cmd = buildString {
                        append("clear\n")
                        append("echo '============================================='\n")
                        append("echo ' LiteBrowser Terminal'\n")
                        append("echo '============================================='\n")
                        if (!hasStorage) {
                            append("echo '⚠️  Storage permission not granted!'\n")
                            append("echo '    Go to Settings > Apps > Lite Browser >'\n")
                            append("echo '    Permissions > All files access'\n")
                        } else {
                            append("echo '📁 Current directory: '\$(pwd)\n")
                        }
                        append("echo '============================================='\n")
                    }
                    terminalSession?.write(cmd.toByteArray(), 0, cmd.length)
                }, 500)
            }
        } catch (e: Throwable) {
            Logger.e("TerminalActivity", "startTerminalSession crashed", e)
        }
    }

    override fun onBackPressed() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.isAcceptingText) {
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try { terminalSession?.finishIfRunning() } catch (e: Exception) {}
        terminalSession = null
        super.onDestroy()
    }

    // ===== TerminalSessionClient =====
    override fun onTextChanged(session: TerminalSession?) {}
    override fun onTitleChanged(session: TerminalSession?) {}
    override fun onSessionFinished(session: TerminalSession?) { terminalSession = null }
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession?) {}
    override fun onColorsChanged(session: TerminalSession?) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) { Logger.e("Session/$tag", message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Logger.w("Session/$tag", message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Logger.i("Session/$tag", message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Logger.d("Session/$tag", message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Logger.d("Session/$tag", message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Logger.e("Session/$tag", message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) {
        Logger.e("Session/$tag", "exception", e)
    }

    // ===== TerminalViewClient =====
    override fun onScale(scale: Float): Float = 1.0f
    override fun onSingleTapUp(e: MotionEvent?) { showKeyboard() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() { Logger.i("TerminalActivity", "onEmulatorSet ✅") }
}
