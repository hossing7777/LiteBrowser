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
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var homeDir: String
    private var isHandlingText = false
    private var currentFontSize = 30f
    private var ctrlPressed = false
    private var altPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("TerminalActivity", "onCreate()")

        try {
            setContentView(R.layout.activity_terminal)

            // تشخیص ارتفاع کیبورد و هل دادن نوار دکمه‌ها به بالا
            val rootLayout = findViewById<LinearLayout>(R.id.root_layout)
            ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
                val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(
                    bottom = imeInsets.bottom.coerceAtLeast(systemBars.bottom)
                )
                insets
            }

            terminalView = findViewById(R.id.terminal_view)
            terminalView.setTerminalViewClient(this)
            hiddenInput = findViewById(R.id.hidden_input)

            findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
            findViewById<Button>(R.id.btn_keyboard).setOnClickListener { showKeyboard() }
            terminalView.setOnClickListener { showKeyboard() }

            // ==== دکمه‌های نوار اضافی ====

            // ESC
            findViewById<Button>(R.id.btn_esc).setOnClickListener {
                sendToTerminal("\u001B")
            }

            // TAB (تکمیل خودکار)
            findViewById<Button>(R.id.btn_tab).setOnClickListener {
                sendToTerminal("\t")
            }

            // CTRL (toggle)
            findViewById<Button>(R.id.btn_ctrl).apply {
                setOnClickListener {
                    ctrlPressed = !ctrlPressed
                    alpha = if (ctrlPressed) 1.0f else 0.5f
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (ctrlPressed) 0xFF0066CC.toInt() else 0xFF444444.toInt()
                    )
                    Logger.i("TerminalActivity", "CTRL pressed: $ctrlPressed")
                }
                alpha = 0.5f
            }

            // ALT (toggle)
            findViewById<Button>(R.id.btn_alt).apply {
                setOnClickListener {
                    altPressed = !altPressed
                    alpha = if (altPressed) 1.0f else 0.5f
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (altPressed) 0xFF0066CC.toInt() else 0xFF444444.toInt()
                    )
                    Logger.i("TerminalActivity", "ALT pressed: $altPressed")
                }
                alpha = 0.5f
            }

            // Arrow keys
            findViewById<Button>(R.id.btn_up).setOnClickListener { sendToTerminal("\u001B[A") }
            findViewById<Button>(R.id.btn_down).setOnClickListener { sendToTerminal("\u001B[B") }
            findViewById<Button>(R.id.btn_right).setOnClickListener { sendToTerminal("\u001B[C") }
            findViewById<Button>(R.id.btn_left).setOnClickListener { sendToTerminal("\u001B[D") }

            // HOME / END
            findViewById<Button>(R.id.btn_home).setOnClickListener { sendToTerminal("\u001B[H") }
            findViewById<Button>(R.id.btn_end).setOnClickListener { sendToTerminal("\u001B[F") }

            // Ctrl+C
            findViewById<Button>(R.id.btn_ctrl_c).setOnClickListener {
                sendToTerminal("\u0003")
                // Ctrl را غیرفعال کن
                ctrlPressed = false
                findViewById<Button>(R.id.btn_ctrl).apply {
                    alpha = 0.5f
                    backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                }
            }

            // Clear
            findViewById<Button>(R.id.btn_clear).setOnClickListener {
                sendToTerminal("clear\r")
            }

            // character keys
            findViewById<Button>(R.id.btn_slash).setOnClickListener { sendToTerminal("/") }
            findViewById<Button>(R.id.btn_pipe).setOnClickListener { sendToTerminal("|") }
            findViewById<Button>(R.id.btn_tilde).setOnClickListener { sendToTerminal("~") }
            findViewById<Button>(R.id.btn_dash).setOnClickListener { sendToTerminal("-") }
            findViewById<Button>(R.id.btn_backslash).setOnClickListener { sendToTerminal("\\\\") }

            // ==== مدیریت تایپ ====

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
                    hiddenInput.setText("")
                    isHandlingText = false
                }
            })

            // Enter از کیبورد
            hiddenInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_NEXT ||
                    actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_NULL) {
                    sendToTerminal("\r")
                    true
                } else false
            }

            hiddenInput.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DEL -> { sendToTerminal("\u007F"); true }
                        KeyEvent.KEYCODE_ENTER -> { sendToTerminal("\r"); true }
                        else -> false
                    }
                } else false
            }

            hiddenInput.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    hiddenInput.postDelayed({ showKeyboard() }, 100)
                }
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

        } catch (e: Throwable) {
            Logger.e("TerminalActivity", "onCreate failed", e)
        }
    }

    private fun sendToTerminal(text: String) {
        try {
            // اگر Ctrl فعاله، حرف رو به کاراکتر کنترلی تبدیل کن
            var finalText = text
            if (ctrlPressed && text.length == 1) {
                val c = text[0].lowercaseChar()
                if (c in 'a'..'z') {
                    finalText = (c - 'a' + 1).toChar().toString()
                    // Ctrl بعد از یک بار استفاده غیرفعال بشه
                    ctrlPressed = false
                    findViewById<Button>(R.id.btn_ctrl).apply {
                        alpha = 0.5f
                        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    }
                }
            }

            val bytes = finalText.toByteArray(Charsets.UTF_8)
            terminalSession?.write(bytes, 0, bytes.size)

            Handler(Looper.getMainLooper()).post {
                terminalView.setTopRow(0)
                terminalView.invalidate()
            }
        } catch (e: Exception) {
            Logger.e("TerminalActivity", "sendToTerminal failed", e)
        }
    }

    private fun getStorageRoot(): String {
        val candidates = listOf("/storage/emulated/0", "/sdcard", Environment.getExternalStorageDirectory().absolutePath)
        for (path in candidates) {
            val f = File(path)
            if (f.exists() && f.canRead()) return path
        }
        return filesDir.absolutePath
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
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

    private fun startTerminalSession(viewWidth: Int, viewHeight: Int) {
        try {
            homeDir = if (checkStoragePermission()) getStorageRoot() else filesDir.absolutePath

            val envVars = arrayOf(
                "PATH=/system/bin:/system/xbin:/system/bin",
                "HOME=$homeDir",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8"
            )

            terminalView.setTextSize(currentFontSize.toInt())

            val session = TerminalSession(
                "/system/bin/sh",
                homeDir,
                arrayOf("-l"),
                envVars,
                2000,
                this
            )

            val cols = (viewWidth / 25).coerceAtLeast(20)
            val rows = (viewHeight / 50).coerceAtLeast(10)
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
    override fun onTextChanged(session: TerminalSession?) {
        try {
            // اسکرول با تاخیر بسیار کم برای اطمینان از رندر شدن
            Handler(Looper.getMainLooper()).postDelayed({
                terminalView.setTopRow(0)
                terminalView.invalidate()
            }, 50)
        } catch (e: Exception) {}
    }
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
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Logger.e("Session/$tag", message ?: "", e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Logger.e("Session/$tag", "exception", e) }

    // ===== TerminalViewClient =====
    override fun onScale(scale: Float): Float {
        return try {
            currentFontSize = (currentFontSize * scale).coerceIn(10f, 80f)
            terminalView.setTextSize(currentFontSize.toInt())
            terminalView.post { terminalView.setTopRow(0) }
            1.0f
        } catch (e: Exception) {
            1.0f
        }
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
    override fun onEmulatorSet() { Logger.i("TerminalActivity", "onEmulatorSet ✅") }
}
