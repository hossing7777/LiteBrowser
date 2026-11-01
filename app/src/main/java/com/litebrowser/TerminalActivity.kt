package com.litebrowser

import android.app.Activity
import android.content.Context
import android.os.Bundle
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

class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null
    private var sessionAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("TerminalActivity", "onCreate()")

        try {
            setContentView(R.layout.activity_terminal)

            terminalView = findViewById(R.id.terminal_view)
            terminalView.setTerminalViewClient(this)

            // دکمه Back
            findViewById<Button>(R.id.btn_back).setOnClickListener {
                Logger.i("TerminalActivity", "Back button clicked")
                finish()
            }

            // دکمه کیبورد
            findViewById<Button>(R.id.btn_keyboard).setOnClickListener {
                showKeyboard()
            }

            // کلیک روی TerminalView هم کیبورد رو نشون بده
            terminalView.setOnClickListener {
                showKeyboard()
            }

            // صبر برای layout
            terminalView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    val w = right - left
                    val h = bottom - top
                    Logger.i("TerminalActivity", "onLayoutChange: ${w}x${h}")

                    if (w > 0 && h > 0 && !sessionAttached) {
                        sessionAttached = true
                        v.removeOnLayoutChangeListener(this)
                        startTerminalSession(w, h)
                    }
                }
            })

            // fallback
            Handler(Looper.getMainLooper()).postDelayed({
                if (!sessionAttached && terminalView.width > 0 && terminalView.height > 0) {
                    sessionAttached = true
                    startTerminalSession(terminalView.width, terminalView.height)
                }
            }, 1000)

        } catch (e: Throwable) {
            Logger.e("TerminalActivity", "onCreate failed", e)
            throw e
        }
    }

    private fun showKeyboard() {
        try {
            terminalView.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            Logger.i("TerminalActivity", "Keyboard requested")
        } catch (e: Exception) {
            Logger.e("TerminalActivity", "showKeyboard failed", e)
        }
    }

    private fun startTerminalSession(viewWidth: Int, viewHeight: Int) {
        Logger.i("TerminalActivity", "startTerminalSession: ${viewWidth}x${viewHeight}")
        try {
            val filesDirPath = filesDir.absolutePath

            val envVars = arrayOf(
                "PATH=/system/bin:/system/xbin:/system/bin",
                "HOME=$filesDirPath",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8"
            )

            terminalView.setTextSize(30)
            Logger.i("TerminalActivity", "setTextSize done")

            val session = TerminalSession(
                "/system/bin/sh",
                filesDirPath,
                arrayOf("-l"),
                envVars,
                2000,
                this
            )
            Logger.i("TerminalActivity", "Session created, running=${session.isRunning}")

            val cols = (viewWidth / 25).coerceAtLeast(20)
            val rows = (viewHeight / 50).coerceAtLeast(10)
            session.updateSize(cols, rows)

            if (session.isRunning) {
                terminalSession = session
                terminalView.attachSession(session)
                Logger.i("TerminalActivity", "attachSession done ✅")

                // فوکوس + کیبورد
                Handler(Looper.getMainLooper()).postDelayed({
                    showKeyboard()
                }, 300)

                // تست
                Handler(Looper.getMainLooper()).postDelayed({
                    val cmd = "echo 'LiteBrowser Terminal Ready!'\n"
                    terminalSession?.write(cmd.toByteArray(), 0, cmd.length)
                }, 500)
            } else {
                Logger.e("TerminalActivity", "Session not running!")
            }
        } catch (e: Throwable) {
            Logger.e("TerminalActivity", "startTerminalSession crashed", e)
        }
    }

    override fun onBackPressed() {
        Logger.i("TerminalActivity", "onBackPressed")
        // اگر کیبورد باز است، اول ببند
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.isAcceptingText) {
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        Logger.i("TerminalActivity", "onDestroy()")
        try {
            terminalSession?.finishIfRunning()
        } catch (e: Exception) {}
        terminalSession = null
        super.onDestroy()
    }

    // ===== TerminalSessionClient =====
    override fun onTextChanged(session: TerminalSession?) {}
    override fun onTitleChanged(session: TerminalSession?) {}
    override fun onSessionFinished(session: TerminalSession?) {
        Logger.w("TerminalActivity", "Session finished")
        terminalSession = null
    }
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
    override fun onEmulatorSet() {
        Logger.i("TerminalActivity", "onEmulatorSet ✅")
    }
}
