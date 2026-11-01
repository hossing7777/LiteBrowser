package com.litebrowser

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class MainActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalView = findViewById(R.id.terminal_view)
        terminalView.setTerminalViewClient(this)

        startTerminalSession()
    }

    private fun startTerminalSession() {
        val envVars = arrayOf(
            "PATH=/system/bin:/system/xbin",
            "HOME=/data/data/$packageName/files",
            "TERM=xterm-256color"
        )

        try {
            Class.forName("com.termux.terminal.JNI")

            val session = TerminalSession(
                "/system/bin/sh",
                filesDir.absolutePath,
                arrayOf("-l"),
                envVars,
                2000,
                this
            )

            session.updateSize(80, 24)

            if (session.isRunning) {
                terminalSession = session
                terminalView.attachSession(session)

                Handler(Looper.getMainLooper()).postDelayed({
                    val cmd = "echo 'LiteBrowser Terminal Ready!'\n"
                    terminalSession?.write(cmd.toByteArray(), 0, cmd.length)
                }, 1000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        terminalSession?.finishIfRunning()
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
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}

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
    override fun onEmulatorSet() {}
}
