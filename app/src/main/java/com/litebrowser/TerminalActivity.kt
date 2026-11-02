package com.litebrowser

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private lateinit var hiddenInput: EditText
    private var terminalSession: TerminalSession? = null
    private var sessionAttached = false
    private var isHandlingText = false
    private var currentFontSize = 30f
    private var ctrlPressed = false
    private var altPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        val rootLayout = findViewById<LinearLayout>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = imeInsets.bottom.coerceAtLeast(systemBars.bottom))
            insets
        }

        terminalView = findViewById(R.id.terminal_view)
        terminalView.setTerminalViewClient(this)
        hiddenInput = findViewById(R.id.hidden_input)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_keyboard).setOnClickListener { showKeyboard() }
        terminalView.setOnClickListener { showKeyboard() }

        findViewById<Button>(R.id.btn_esc).setOnClickListener { sendToTerminal("\u001B") }
        findViewById<Button>(R.id.btn_tab).setOnClickListener { sendToTerminal("\t") }

        findViewById<Button>(R.id.btn_ctrl).apply {
            alpha = 0.5f
            setOnClickListener {
                ctrlPressed = !ctrlPressed
                alpha = if (ctrlPressed) 1.0f else 0.5f
            }
        }
        findViewById<Button>(R.id.btn_alt).apply {
            alpha = 0.5f
            setOnClickListener {
                altPressed = !altPressed
                alpha = if (altPressed) 1.0f else 0.5f
            }
        }
        findViewById<Button>(R.id.btn_up).setOnClickListener { sendToTerminal("\u001B[A") }
        findViewById<Button>(R.id.btn_down).setOnClickListener { sendToTerminal("\u001B[B") }
        findViewById<Button>(R.id.btn_right).setOnClickListener { sendToTerminal("\u001B[C") }
        findViewById<Button>(R.id.btn_left).setOnClickListener { sendToTerminal("\u001B[D") }
        findViewById<Button>(R.id.btn_home).setOnClickListener { sendToTerminal("\u001B[H") }
        findViewById<Button>(R.id.btn_end).setOnClickListener { sendToTerminal("\u001B[F") }
        findViewById<Button>(R.id.btn_ctrl_c).setOnClickListener { sendToTerminal("\u0003") }
        findViewById<Button>(R.id.btn_clear).setOnClickListener { sendToTerminal("clear\r") }
        findViewById<Button>(R.id.btn_slash).setOnClickListener { sendToTerminal("/") }
        findViewById<Button>(R.id.btn_backslash).setOnClickListener { sendToTerminal("\\\\") }
        findViewById<Button>(R.id.btn_pipe).setOnClickListener { sendToTerminal("|") }
        findViewById<Button>(R.id.btn_tilde).setOnClickListener { sendToTerminal("~") }
        findViewById<Button>(R.id.btn_dash).setOnClickListener { sendToTerminal("-") }

        hiddenInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isHandlingText || s == null || s.isEmpty()) return
                val text = s.toString()
                if (text.contains("\n")) {
                    val parts = text.split("\n")
                    for (i in parts.indices) {
                        if (parts[i].isNotEmpty()) sendToTerminal(parts[i])
                        if (i < parts.size - 1) sendToTerminal("\r")
                    }
                } else if (text.length == 1 && text[0] == '\u007F') {
                    sendToTerminal("\u007F")
                } else {
                    sendToTerminal(text)
                }
                isHandlingText = true
                hiddenInput.text.clear()
                isHandlingText = false
            }
        })

        hiddenInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DEL -> { sendToTerminal("\u007F"); true }
                    KeyEvent.KEYCODE_ENTER -> { sendToTerminal("\r"); true }
                    else -> false
                }
            } else false
        }

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
    }

    private fun startTerminalSession(viewWidth: Int, viewHeight: Int) {
        try {
            val app = application as LiteApp
            val prefixDir = File(app.filesDir, "usr")
            val binDir = File(prefixDir, "bin")
            val libDir = File(prefixDir, "lib")
            val cwdDir = app.filesDir
            val cacheDir = app.cacheDir
            val nativeDir = app.applicationInfo.nativeLibraryDir

            val envVars = arrayOf(
                "PATH=${binDir.absolutePath}:/system/bin:/system/xbin",
                "HOME=${cwdDir.absolutePath}",
                "PREFIX=${prefixDir.absolutePath}",
                "LD_LIBRARY_PATH=${libDir.absolutePath}:$nativeDir",
                "APP_PREFIX=${prefixDir.absolutePath}",
                "APP_HOME=${cwdDir.absolutePath}",
                "APP_PACKAGE=${app.packageName}",
                "PROOT_LOADER=$nativeDir/libproot-loader.so",
                "PROOT_NO_SECCOMP=1",
                "PROOT_TMP_DIR=${cacheDir.absolutePath}",
                "TMPDIR=${cacheDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "APP_DNS_SERVERS=" + (app.getProotHost().dnsServers.joinToString(","))
            )

            terminalView.setTextSize(currentFontSize.toInt())
            val cols = (viewWidth / 25).coerceAtLeast(20)
            val rows = (viewHeight / 50).coerceAtLeast(10)

            val session = TerminalSession(
                "/system/bin/sh",
                cwdDir.absolutePath,
                arrayOf("-l"),
                envVars, 2000, this
            )
            session.updateSize(cols, rows)

            if (session.isRunning) {
                terminalSession = session
                terminalView.attachSession(session)
                Handler(Looper.getMainLooper()).postDelayed({ showKeyboard() }, 300)
            }
        } catch (e: Throwable) {
            Logger.e("TerminalActivity", "startTerminalSession crashed", e)
        }
    }

    private fun sendToTerminal(text: String) {
        try {
            var finalText = text
            if (ctrlPressed && text.length == 1) {
                val c = text[0].lowercaseChar()
                if (c in 'a'..'z') {
                    finalText = (c - 'a' + 1).toChar().toString()
                    ctrlPressed = false
                }
            }
            val bytes = finalText.toByteArray(Charsets.UTF_8)
            terminalSession?.write(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Logger.e("TerminalActivity", "sendToTerminal failed", e)
        }
    }

    private fun showKeyboard() {
        try {
            hiddenInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(hiddenInput, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
            Logger.e("TerminalActivity", "showKeyboard failed", e)
        }
    }

    override fun onBackPressed() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.isAcceptingText) imm.hideSoftInputFromWindow(hiddenInput.windowToken, 0)
        else super.onBackPressed()
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
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}

    // ===== TerminalViewClient =====
    override fun onScale(scale: Float): Float {
        return try {
            currentFontSize = (currentFontSize * scale).coerceIn(10f, 80f)
            terminalView.setTextSize(currentFontSize.toInt())
            terminalView.post { terminalView.setTopRow(0) }
            1.0f
        } catch (e: Exception) { 1.0f }
    }
    override fun onSingleTapUp(e: MotionEvent?) { showKeyboard() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = ctrlPressed
    override fun readAltKey(): Boolean = altPressed
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {}
}
