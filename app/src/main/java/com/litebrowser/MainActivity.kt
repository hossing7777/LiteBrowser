package com.litebrowser

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

class MainActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("MainActivity", "onCreate() called")

        try {
            setContentView(R.layout.activity_main)
            Logger.i("MainActivity", "Layout set")

            terminalView = findViewById(R.id.terminal_view)
            Logger.i("MainActivity", "TerminalView found: $terminalView")

            terminalView.setTerminalViewClient(this)
            Logger.i("MainActivity", "TerminalViewClient set")

            terminalView.post {
                Logger.i("MainActivity", "post() callback triggered, attaching session")
                startTerminalSession()
            }
        } catch (e: Throwable) {
            Logger.e("MainActivity", "onCreate failed", e)
            throw e
        }
    }

    private fun startTerminalSession() {
        Logger.i("MainActivity", "startTerminalSession() called")
        try {
            val filesDirPath = filesDir.absolutePath
            Logger.i("MainActivity", "filesDir = $filesDirPath")
            Logger.i("MainActivity", "filesDir exists = ${File(filesDirPath).exists()}")

            val envVars = arrayOf(
                "PATH=/system/bin:/system/xbin:/system/bin",
                "HOME=$filesDirPath",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8"
            )
            Logger.i("MainActivity", "env = ${envVars.joinToString(", ")}")

            Logger.i("MainActivity", "Creating TerminalSession...")
            val session = TerminalSession(
                "/system/bin/sh",
                filesDirPath,
                arrayOf("-l"),
                envVars,
                2000,
                this
            )
            Logger.i("MainActivity", "TerminalSession created: $session, running=${session.isRunning}")

            session.updateSize(80, 24)
            Logger.i("MainActivity", "Session size updated to 80x24")

            if (session.isRunning) {
                terminalSession = session
                Logger.i("MainActivity", "Calling terminalView.attachSession...")
                terminalView.attachSession(session)
                Logger.i("MainActivity", "attachSession returned")

                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val cmd = "echo 'LiteBrowser Terminal Ready!'\n"
                        Logger.i("MainActivity", "Writing test command: $cmd")
                        terminalSession?.write(cmd.toByteArray(), 0, cmd.length)
                        Logger.i("MainActivity", "Test command written")
                    } catch (e: Exception) {
                        Logger.e("MainActivity", "write failed", e)
                    }
                }, 1000)
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
            Logger.i("MainActivity", "Session finishIfRunning called")
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
        Logger.w("SessionClient", "onSessionFinished: exitStatus=${session?.exitStatus}")
        terminalSession = null
    }
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
        Logger.i("SessionClient", "onCopyTextToClipboard: ${text?.take(50)}")
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        Logger.i("SessionClient", "onPasteTextFromClipboard")
    }
    override fun onBell(session: TerminalSession?) {
        Logger.i("SessionClient", "onBell")
    }
    override fun onColorsChanged(session: TerminalSession?) {
        Logger.d("SessionClient", "onColorsChanged")
    }
    override fun onTerminalCursorStateChange(state: Boolean) {
        Logger.d("SessionClient", "onTerminalCursorStateChange: $state")
    }
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
    override fun onScale(scale: Float): Float {
        Logger.d("ViewClient", "onScale: $scale")
        return 1.0f
    }
    override fun onSingleTapUp(e: MotionEvent?) {
        Logger.d("ViewClient", "onSingleTapUp")
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {
        Logger.d("ViewClient", "copyModeChanged: $copyMode")
    }
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        Logger.d("ViewClient", "onKeyDown: $keyCode")
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        Logger.d("ViewClient", "onCodePoint: $codePoint, ctrl=$ctrlDown")
        return false
    }
    override fun onEmulatorSet() {
        Logger.i("ViewClient", "onEmulatorSet called - emulator ready")
    }
}
