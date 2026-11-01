package com.litebrowser

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null
    private var sessionAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("MainActivity", "onCreate() called")

        try {
            setContentView(R.layout.activity_terminal)
            Logger.i("MainActivity", "Layout set")

            terminalView = findViewById(R.id.terminal_view)
            Logger.i("MainActivity", "TerminalView found: $terminalView")

            terminalView.setTerminalViewClient(this)

            // ⚠️ مهم: باید صبر کنیم تا View اندازه واقعی بگیره
            terminalView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    val w = right - left
                    val h = bottom - top
                    Logger.i("MainActivity", "onLayoutChange: ${w}x${h}")

                    if (w > 0 && h > 0 && !sessionAttached) {
                        sessionAttached = true
                        v.removeOnLayoutChangeListener(this)
                        startTerminalSession(w, h)
                    }
                }
            })

            // Fallback: اگر layout change نیومد (خیلی نادر)
            Handler(Looper.getMainLooper()).postDelayed({
                if (!sessionAttached) {
                    val w = terminalView.width
                    val h = terminalView.height
                    Logger.w("MainActivity", "Fallback timer: ${w}x${h}")
                    if (w > 0 && h > 0) {
                        sessionAttached = true
                        startTerminalSession(w, h)
                    }
                }
            }, 1000)

        } catch (e: Throwable) {
            Logger.e("MainActivity", "onCreate failed", e)
            throw e
        }
    }

    private fun startTerminalSession(viewWidth: Int, viewHeight: Int) {
        Logger.i("MainActivity", "startTerminalSession: ${viewWidth}x${viewHeight}")
        try {
            val filesDirPath = filesDir.absolutePath
            Logger.i("MainActivity", "filesDir = $filesDirPath")

            val envVars = arrayOf(
                "PATH=/system/bin:/system/xbin:/system/bin",
                "HOME=$filesDirPath",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8"
            )

            // ۱. اول textSize رو ست کن تا mRenderer ساخته بشه
            // این مهم‌ترین نکته است!
            Logger.i("MainActivity", "setTextSize(30)...")
            terminalView.setTextSize(30)
            Logger.i("MainActivity", "setTextSize done")

            // ۲. حالا session رو بساز
            Logger.i("MainActivity", "Creating TerminalSession...")
            val session = TerminalSession(
                "/system/bin/sh",
                filesDirPath,
                arrayOf("-l"),
                envVars,
                2000,
                this
            )
            Logger.i("MainActivity", "Session created: $session, running=${session.isRunning}")

            // ۳. اندازه بر اساس view
            val cols = (viewWidth / 25).coerceAtLeast(20)
            val rows = (viewHeight / 50).coerceAtLeast(10)
            Logger.i("MainActivity", "updateSize($cols, $rows)")
            session.updateSize(cols, rows)

            if (session.isRunning) {
                terminalSession = session

                // ۴. الان attach کن — mRenderer آماده است
                Logger.i("MainActivity", "attachSession()...")
                terminalView.attachSession(session)
                Logger.i("MainActivity", "attachSession returned ✅")

                // ۵. تست
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val cmd = "echo 'LiteBrowser Terminal Ready!'\n"
                        terminalSession?.write(cmd.toByteArray(), 0, cmd.length)
                        Logger.i("MainActivity", "Test command written")
                    } catch (e: Exception) {
                        Logger.e("MainActivity", "write failed", e)
                    }
                }, 500)
            } else {
                Logger.e("MainActivity", "Session is NOT running!")
            }
        } catch (e: Throwable) {
            Logger.e("MainActivity", "startTerminalSession crashed", e)
        }
    }

    override fun onDestroy() {
        Logger.i("MainActivity", "onDestroy() called")
        try {
            terminalSession?.finishIfRunning()
        } catch (e: Exception) {
            Logger.e("MainActivity", "onDestroy error", e)
        }
        terminalSession = null
        super.onDestroy()
    }

    // ===== TerminalSessionClient =====
    override fun onTextChanged(session: TerminalSession?) {
        Logger.d("SessionClient", "onTextChanged")
    }
    override fun onTitleChanged(session: TerminalSession?) {
        Logger.i("SessionClient", "onTitleChanged: ${session?.title}")
    }
    override fun onSessionFinished(session: TerminalSession?) {
        Logger.w("SessionClient", "onSessionFinished: exit=${session?.exitStatus}")
        terminalSession = null
    }
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession?) {
        Logger.i("SessionClient", "onBell")
    }
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
    override fun onSingleTapUp(e: MotionEvent?) {}
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
    override fun onEmulatorSet() {
        Logger.i("ViewClient", "onEmulatorSet — emulator ready ✅")
    }
}
