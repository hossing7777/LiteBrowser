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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private lateinit var spinnerSessions: Spinner
    private var terminalSession: TerminalSession? = null
    private var sessionAttached = false
    private var currentFontSize = 18f
    private var ctrlPressed = false
    private var altPressed = false
    private val handler = Handler(Looper.getMainLooper())
    private var isUpdatingSpinner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TerminalManager.setClient(this)
        
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_terminal)

        terminalView = findViewById(R.id.terminal_view)
        spinnerSessions = findViewById(R.id.spinner_sessions)
        
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.setTerminalViewClient(this)

        setupButtons()
        setupSpinner()

        terminalView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                if (right > left && bottom > top && !sessionAttached) {
                    // اگر هیچ جلسه‌ای وجود ندارد، یکی بساز
                    if (TerminalManager.sessions.isEmpty()) {
                        createNewSession(right - left, bottom - top)
                    } else {
                        // در غیر این صورت به آخرین جلسه وصل شو
                        attachToSession(TerminalManager.sessions.last(), right - left, bottom - top)
                    }
                }
            }
        })
        terminalView.requestFocus()
    }

    private fun setupSpinner() {
        spinnerSessions.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingSpinner) return
                val session = TerminalManager.sessions.getOrNull(position)
                if (session != null && session != terminalSession) {
                    attachToSession(session, terminalView.width, terminalView.height)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun updateSpinner() {
        isUpdatingSpinner = true
        val sessionNames = TerminalManager.sessions.mapIndexed { index, session ->
            val title = session.getTitle() ?: "Session ${index + 1}"
            "$title (PID: ${session.getPid()})"
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sessionNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSessions.adapter = adapter
        
        // انتخاب جلسه فعلی در منو
        val currentIndex = TerminalManager.sessions.indexOf(terminalSession)
        if (currentIndex >= 0) {
            spinnerSessions.setSelection(currentIndex)
        }
        isUpdatingSpinner = false
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_keyboard).setOnClickListener { showKeyboard() }
        findViewById<Button>(R.id.btn_new_session).setOnClickListener { 
            createNewSession(terminalView.width, terminalView.height) 
        }
        
        findViewById<Button>(R.id.btn_esc).setOnClickListener { sendToTerminal("\u001B") }
        findViewById<Button>(R.id.btn_tab).setOnClickListener { sendToTerminal("\t") }
        findViewById<Button>(R.id.btn_ctrl).apply {
            alpha = 0.5f
            setOnClickListener { ctrlPressed = !ctrlPressed; alpha = if (ctrlPressed) 1f else 0.5f; terminalView.requestFocus() }
        }
        findViewById<Button>(R.id.btn_alt).apply {
            alpha = 0.5f
            setOnClickListener { altPressed = !altPressed; alpha = if (altPressed) 1f else 0.5f; terminalView.requestFocus() }
        }
        findViewById<Button>(R.id.btn_up).setOnClickListener { sendToTerminal("\u001B[A") }
        findViewById<Button>(R.id.btn_down).setOnClickListener { sendToTerminal("\u001B[B") }
        findViewById<Button>(R.id.btn_left).setOnClickListener { sendToTerminal("\u001B[D") }
        findViewById<Button>(R.id.btn_right).setOnClickListener { sendToTerminal("\u001B[C") }
        findViewById<Button>(R.id.btn_home).setOnClickListener { sendToTerminal("\u001B[H") }
        findViewById<Button>(R.id.btn_end).setOnClickListener { sendToTerminal("\u001B[F") }
        findViewById<Button>(R.id.btn_ctrl_c).setOnClickListener { sendToTerminal("\u0003") }
        findViewById<Button>(R.id.btn_clear).setOnClickListener { sendToTerminal("clear\r") }
        findViewById<Button>(R.id.btn_slash).setOnClickListener { sendToTerminal("/") }
        findViewById<Button>(R.id.btn_backslash).setOnClickListener { sendToTerminal("\\") }
        findViewById<Button>(R.id.btn_pipe).setOnClickListener { sendToTerminal("|") }
        findViewById<Button>(R.id.btn_tilde).setOnClickListener { sendToTerminal("~") }
        findViewById<Button>(R.id.btn_dash).setOnClickListener { sendToTerminal("-") }
        findViewById<Button>(R.id.btn_paste).setOnClickListener { pasteFromClipboard() }
    }

    private fun createNewSession(viewWidth: Int, viewHeight: Int) {
        try {
            val app = application as LiteApp
            val prefixDir = File(app.filesDir, "usr")
            val homeDir = File(app.filesDir, "home").also { it.mkdirs() }
            val tmpDir = File(app.cacheDir, "proot-tmp").also { it.mkdirs() }
            val nativeDir = app.applicationInfo.nativeLibraryDir

            val prootBin = File(nativeDir, "libproot.so")
            val prootLoader = File(nativeDir, "libproot-loader.so")
            val alpineRoot = File(prefixDir, "var/lib/pr/installed-rootfs/alpine")

            val envVars = arrayOf(
                "PATH=${nativeDir}:${prefixDir.absolutePath}/bin:/system/bin:/system/xbin",
                "HOME=${homeDir.absolutePath}",
                "PREFIX=${prefixDir.absolutePath}",
                "LD_LIBRARY_PATH=${nativeDir}:${prefixDir.absolutePath}/lib",
                "APP_PREFIX=${prefixDir.absolutePath}",
                "APP_HOME=${homeDir.absolutePath}",
                "APP_PACKAGE=${app.packageName}",
                "PROOT_LOADER=${prootLoader.absolutePath}",
                "PROOT_NO_SECCOMP=1",
                "PROOT_TMP_DIR=${tmpDir.absolutePath}",
                "TMPDIR=${tmpDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8"
            )

            val args = arrayOf(
                prootBin.absolutePath, "--link2symlink", "-0",
                "-r", alpineRoot.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-w", "/root", "/bin/sh", "-l"
            )

            val session = TerminalSession(
                prootBin.absolutePath, homeDir.absolutePath, args, envVars, 2000, TerminalManager
            )
            
            TerminalManager.addSession(session)
            // اتصال به جلسه جدید
            attachToSession(session, viewWidth, viewHeight)
            updateSpinner()
            
        } catch (e: Throwable) {
            Logger.e("Terminal", "Create session failed", e)
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun attachToSession(session: TerminalSession, viewWidth: Int, viewHeight: Int) {
        if (terminalSession === session && sessionAttached) return
        
        terminalView.setTextSize(currentFontSize.toInt())
        val cols = (viewWidth / (currentFontSize * 0.62f)).toInt().coerceIn(20, 200)
        val rows = (viewHeight / (currentFontSize * 1.35f)).toInt().coerceIn(8, 100)
        session.updateSize(cols, rows)
        
        terminalSession = session
        terminalView.attachSession(session)
        sessionAttached = true
        terminalView.requestFocus()
        handler.postDelayed({ showKeyboard() }, 500)
        
        updateSpinner()
    }

    private fun sendToTerminal(text: String) {
        try {
            val session = terminalSession ?: return
            if (!session.isRunning) return
            var output = text
            if (ctrlPressed && text.length == 1) {
                val c = text[0].lowercaseChar()
                if (c in 'a'..'z') {
                    output = (c.code - 96).toChar().toString()
                    ctrlPressed = false
                    findViewById<Button>(R.id.btn_ctrl).alpha = 0.5f
                }
            }
            if (altPressed && output.length == 1) {
                output = "\u001B$output"
                altPressed = false
                findViewById<Button>(R.id.btn_alt).alpha = 0.5f
            }
            val bytes = output.toByteArray(Charsets.UTF_8)
            session.write(bytes, 0, bytes.size)
            terminalView.requestFocus()
        } catch (e: Exception) {
            Logger.e("Terminal", "Input error", e)
        }
    }

    private fun showKeyboard() {
        if (!::terminalView.isInitialized) return
        terminalView.post {
            try {
                terminalView.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.restartInput(terminalView)
                imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) {}
        }
    }

    private fun pasteFromClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.let { sendToTerminal(it.toString()) }
            }
        } catch (e: Exception) {}
    }

    override fun onBackPressed() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.isAcceptingText) imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
        else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        TerminalManager.setClient(null)
        terminalSession = null
        super.onDestroy()
    }

    override fun onTextChanged(session: TerminalSession?) { 
        runOnUiThread { 
            terminalView.invalidate()
            if (session == terminalSession) updateSpinner() 
        } 
    }
    override fun onTitleChanged(session: TerminalSession?) { 
        runOnUiThread { updateSpinner() } 
    }
    override fun onSessionFinished(session: TerminalSession?) {
        runOnUiThread {
            if (terminalSession === session) {
                terminalSession = null
                sessionAttached = false
                // رفتن به جلسه بعدی اگر وجود دارد
                val nextSession = TerminalManager.sessions.firstOrNull()
                if (nextSession != null) {
                    attachToSession(nextSession, terminalView.width, terminalView.height)
                } else {
                    Toast.makeText(this, "All sessions ended", Toast.LENGTH_SHORT).show()
                    finish() // بستن صفحه اگر هیچ جلسه‌ای نماند
                }
            }
            updateSpinner()
        }
    }
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text ?: ""))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) { pasteFromClipboard() }
    override fun onBell(session: TerminalSession?) {}
    override fun onColorsChanged(session: TerminalSession?) { runOnUiThread { terminalView.invalidate() } }
    override fun onTerminalCursorStateChange(state: Boolean) { runOnUiThread { terminalView.invalidate() } }
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}

    override fun onScale(scale: Float): Float {
        return try {
            currentFontSize = (currentFontSize * scale).coerceIn(10f, 32f)
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
