package com.litebrowser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

class MainActivity : AppCompatActivity(), TerminalSessionClient {

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
            "HOME=/data/data/${packageName}/files",
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

            session.updateSize(80, 24, 0, 0)

            if (session.isRunning) {
                terminalSession = session
                terminalView.attachSession(session)

                // یک دستور تست بعد از بالا اومدن
                Handler(Looper.getMainLooper()).postDelayed({
                    val cmd = "echo 'LiteBrowser Terminal Ready! Type commands below:'\n"
                    terminalSession?.write(cmd.toByteArray(), 0, cmd.length)
                }, 1000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        terminalSession?.finish()
        terminalSession = null
        super.onDestroy()
    }

    override fun onTextChanged(session: TerminalSession?) {}
    override fun onTitleChanged(session: TerminalSession?) {}
    override fun onSessionFinished(session: TerminalSession?) { terminalSession = null }
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession?) {}
    override fun onColorsChanged(session: TerminalSession?) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 0
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
