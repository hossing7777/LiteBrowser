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
import android.widget.LinearLayout
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

class TerminalActivity : Activity(),
    TerminalSessionClient,
    TerminalViewClient {

    private lateinit var terminalView: TerminalView

    private var terminalSession: TerminalSession? = null

    private var sessionAttached = false
    private var sessionStarting = false

    private var currentFontSize = 18f

    private var ctrlPressed = false
    private var altPressed = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        setContentView(R.layout.activity_terminal)

        terminalView = findViewById(R.id.terminal_view)

        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true

        terminalView.setTerminalViewClient(this)

        setupButtons()

        terminalView.addOnLayoutChangeListener(
            object : View.OnLayoutChangeListener {

                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int
                ) {

                    if (
                        right > left &&
                        bottom > top &&
                        !sessionAttached &&
                        !sessionStarting
                    ) {
                        startTerminalSession(
                            right - left,
                            bottom - top
                        )
                    }
                }
            }
        )

        terminalView.requestFocus()
    }

    private fun setupButtons() {

        findViewById<Button>(R.id.btn_back)
            .setOnClickListener {
                finish()
            }

        findViewById<Button>(R.id.btn_keyboard)
            .setOnClickListener {
                showKeyboard()
            }

        findViewById<Button>(R.id.btn_esc)
            .setOnClickListener {
                sendToTerminal("\u001B")
            }

        findViewById<Button>(R.id.btn_tab)
            .setOnClickListener {
                sendToTerminal("\t")
            }

        findViewById<Button>(R.id.btn_ctrl).apply {

            setOnClickListener {

                ctrlPressed = !ctrlPressed

                alpha = if (ctrlPressed) 1f else 0.5f

                terminalView.requestFocus()
            }

            alpha = 0.5f
        }

        findViewById<Button>(R.id.btn_alt).apply {

            setOnClickListener {

                altPressed = !altPressed

                alpha = if (altPressed) 1f else 0.5f

                terminalView.requestFocus()
            }

            alpha = 0.5f
        }

        findViewById<Button>(R.id.btn_up)
            .setOnClickListener {
                sendToTerminal("\u001B[A")
            }

        findViewById<Button>(R.id.btn_down)
            .setOnClickListener {
                sendToTerminal("\u001B[B")
            }

        findViewById<Button>(R.id.btn_left)
            .setOnClickListener {
                sendToTerminal("\u001B[D")
            }

        findViewById<Button>(R.id.btn_right)
            .setOnClickListener {
                sendToTerminal("\u001B[C")
            }

        findViewById<Button>(R.id.btn_home)
            .setOnClickListener {
                sendToTerminal("\u001B[H")
            }

        findViewById<Button>(R.id.btn_end)
            .setOnClickListener {
                sendToTerminal("\u001B[F")
            }

        findViewById<Button>(R.id.btn_ctrl_c)
            .setOnClickListener {
                sendToTerminal("\u0003")
            }

        findViewById<Button>(R.id.btn_clear)
            .setOnClickListener {
                sendToTerminal("clear\r")
            }

        findViewById<Button>(R.id.btn_slash)
            .setOnClickListener {
                sendToTerminal("/")
            }

        findViewById<Button>(R.id.btn_backslash)
            .setOnClickListener {
                sendToTerminal("\\")
            }

        findViewById<Button>(R.id.btn_pipe)
            .setOnClickListener {
                sendToTerminal("|")
            }

        findViewById<Button>(R.id.btn_tilde)
            .setOnClickListener {
                sendToTerminal("~")
            }

        findViewById<Button>(R.id.btn_dash)
            .setOnClickListener {
                sendToTerminal("-")
            }

        findViewById<Button>(R.id.btn_paste)
            .setOnClickListener {
                pasteFromClipboard()
            }
    }

    private fun startTerminalSession(
        viewWidth: Int,
        viewHeight: Int
    ) {

        if (sessionStarting || sessionAttached) return

        sessionStarting = true

        try {

            val app = application as LiteApp

            val prefixDir = File(app.filesDir, "usr")
            val binDir = File(prefixDir, "bin")
            val libDir = File(prefixDir, "lib")

            val homeDir = File(app.filesDir, "home")
            homeDir.mkdirs()

            val cacheDir = app.cacheDir

            val nativeDir = app.applicationInfo.nativeLibraryDir

            val alpineRoot = File(
                prefixDir,
                "installed/alpine"
            )

            if (!File(alpineRoot, "bin/sh").exists()) {

                throw IllegalStateException(
                    "Alpine Linux is not installed correctly"
                )
            }

            val prCli = NativeInstaller.prCli(app)

            if (!prCli.exists() || !prCli.canExecute()) {

                throw IllegalStateException(
                    "PRoot executable not found"
                )
            }

            val envVars = arrayOf(

                "PATH=${binDir.absolutePath}:/system/bin:/system/xbin",

                "HOME=${homeDir.absolutePath}",

                "PREFIX=${prefixDir.absolutePath}",

                "LD_LIBRARY_PATH=${libDir.absolutePath}:$nativeDir",

                "APP_PREFIX=${prefixDir.absolutePath}",

                "APP_HOME=${homeDir.absolutePath}",

                "APP_PACKAGE=${app.packageName}",

                "PROOT_LOADER=$nativeDir/libproot-loader.so",

                "PROOT_NO_SECCOMP=1",

                "PROOT_TMP_DIR=${cacheDir.absolutePath}",

                "TMPDIR=${cacheDir.absolutePath}",

                "TERM=xterm-256color",

                "LANG=C.UTF-8",

                "LC_ALL=C.UTF-8",

                "APP_DNS_SERVERS=" +
                    app.getProotHost().dnsServers.joinToString(",")
            )

            terminalView.setTextSize(
                currentFontSize.toInt()
            )

            val charWidth = currentFontSize * 0.62f
            val lineHeight = currentFontSize * 1.35f

            val cols = (
                viewWidth / charWidth
            ).toInt().coerceIn(20, 200)

            val rows = (
                viewHeight / lineHeight
            ).toInt().coerceIn(8, 100)

            Logger.i(
                "Terminal",
                "Starting terminal: cols=$cols rows=$rows"
            )

            val session = TerminalSession(

                prCli.absolutePath,

                homeDir.absolutePath,

                arrayOf(
                    "login",
                    "alpine"
                ),

                envVars,

                2000,

                this
            )

            if (!session.isRunning) {

                session.finishIfRunning()

                throw IllegalStateException(
                    "Alpine terminal process failed to start"
                )
            }

            session.updateSize(cols, rows)

            terminalSession = session

            terminalView.attachSession(session)

            sessionAttached = true

            terminalView.requestFocus()

            Logger.i(
                "Terminal",
                "Terminal session attached successfully"
            )

            handler.postDelayed(
                {
                    showKeyboard()
                },
                500
            )

        } catch (e: Throwable) {

            Logger.e(
                "Terminal",
                "Terminal startup failed",
                e
            )

            sessionAttached = false

            Toast.makeText(
                this,
                "Terminal startup failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()

        } finally {

            sessionStarting = false
        }
    }

    private fun sendToTerminal(text: String) {

        try {

            val session = terminalSession

            if (session == null || !session.isRunning) {

                Logger.w(
                    "Terminal",
                    "Cannot send input: session is not running"
                )

                return
            }

            var output = text

            if (ctrlPressed && text.length == 1) {

                val c = text[0].lowercaseChar()

                if (c in 'a'..'z') {

                    output = (c.code - 96).toChar().toString()

                    ctrlPressed = false

                    findViewById<Button>(R.id.btn_ctrl)
                        .alpha = 0.5f
                }
            }

            if (altPressed && output.length == 1) {

                output = "\u001B$output"

                altPressed = false

                findViewById<Button>(R.id.btn_alt)
                    .alpha = 0.5f
            }

            val bytes = output.toByteArray(
                Charsets.UTF_8
            )

            session.write(
                bytes,
                0,
                bytes.size
            )

            terminalView.requestFocus()

        } catch (e: Exception) {

            Logger.e(
                "Terminal",
                "Input error",
                e
            )
        }
    }

    private fun showKeyboard() {

        if (!::terminalView.isInitialized) return

        terminalView.post {

            try {

                terminalView.requestFocus()

                val imm = getSystemService(
                    Context.INPUT_METHOD_SERVICE
                ) as InputMethodManager

                imm.restartInput(terminalView)

                imm.showSoftInput(
                    terminalView,
                    InputMethodManager.SHOW_IMPLICIT
                )

            } catch (e: Exception) {

                Logger.e(
                    "Terminal",
                    "Keyboard error",
                    e
                )
            }
        }
    }

    private fun pasteFromClipboard() {

        try {

            val clipboard = getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

            val clip = clipboard.primaryClip

            if (clip != null && clip.itemCount > 0) {

                val text = clip.getItemAt(0).text

                if (text != null) {
                    sendToTerminal(text.toString())
                }
            }

        } catch (e: Exception) {

            Logger.e(
                "Terminal",
                "Paste failed",
                e
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {

        super.onWindowFocusChanged(hasFocus)

        if (hasFocus && sessionAttached) {

            handler.postDelayed(
                {
                    terminalView.requestFocus()
                },
                150
            )
        }
    }

    override fun onBackPressed() {

        val imm = getSystemService(
            Context.INPUT_METHOD_SERVICE
        ) as InputMethodManager

        if (imm.isAcceptingText) {

            imm.hideSoftInputFromWindow(
                terminalView.windowToken,
                0
            )

        } else {

            super.onBackPressed()
        }
    }

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(null)

        try {

            terminalSession?.finishIfRunning()

        } catch (e: Exception) {

            Logger.e(
                "Terminal",
                "Session cleanup failed",
                e
            )
        }

        terminalSession = null

        super.onDestroy()
    }

    // TerminalSessionClient

    override fun onTextChanged(
        session: TerminalSession?
    ) {
        runOnUiThread {
            terminalView.invalidate()
        }
    }

    override fun onTitleChanged(
        session: TerminalSession?
    ) {
    }

    override fun onSessionFinished(
        session: TerminalSession?
    ) {

        if (terminalSession === session) {
            terminalSession = null
        }

        runOnUiThread {
            Toast.makeText(
                this,
                "Terminal session ended",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCopyTextToClipboard(
        session: TerminalSession?,
        text: String?
    ) {

        val clipboard = getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "Terminal",
                text ?: ""
            )
        )
    }

    override fun onPasteTextFromClipboard(
        session: TerminalSession?
    ) {
        pasteFromClipboard()
    }

    override fun onBell(session: TerminalSession?) {
    }

    override fun onColorsChanged(
        session: TerminalSession?
    ) {
        runOnUiThread {
            terminalView.invalidate()
        }
    }

    override fun onTerminalCursorStateChange(
        state: Boolean
    ) {
        runOnUiThread {
            terminalView.invalidate()
        }
    }

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(
        tag: String?,
        message: String?
    ) {
        Logger.e(
            tag ?: "Terminal",
            message ?: ""
        )
    }

    override fun logWarn(
        tag: String?,
        message: String?
    ) {
        Logger.w(
            tag ?: "Terminal",
            message ?: ""
        )
    }

    override fun logInfo(
        tag: String?,
        message: String?
    ) {
        Logger.i(
            tag ?: "Terminal",
            message ?: ""
        )
    }

    override fun logDebug(
        tag: String?,
        message: String?
    ) {
        Logger.d(
            tag ?: "Terminal",
            message ?: ""
        )
    }

    override fun logVerbose(
        tag: String?,
        message: String?
    ) {
        Logger.d(
            tag ?: "Terminal",
            message ?: ""
        )
    }

    override fun logStackTraceWithMessage(
        tag: String?,
        message: String?,
        e: Exception?
    ) {
        Logger.e(
            tag ?: "Terminal",
            message ?: "",
            e
        )
    }

    override fun logStackTrace(
        tag: String?,
        e: Exception?
    ) {
        Logger.e(
            tag ?: "Terminal",
            "Terminal stack trace",
            e
        )
    }

    // TerminalViewClient

    override fun onScale(scale: Float): Float {

        return try {

            currentFontSize = (
                currentFontSize * scale
            ).coerceIn(10f, 32f)

            terminalView.setTextSize(
                currentFontSize.toInt()
            )

            terminalView.post {
                terminalView.setTopRow(0)
            }

            1.0f

        } catch (e: Exception) {

            1.0f
        }
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        showKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean {
        return false
    }

    // مهم: رفع مشکل Samsung Keyboard
    override fun shouldEnforceCharBasedInput(): Boolean {
        return true
    }

    override fun shouldUseCtrlSpaceWorkaround(): Boolean {
        return false
    }

    override fun isTerminalViewSelected(): Boolean {
        return true
    }

    override fun copyModeChanged(copyMode: Boolean) {
    }

    override fun onKeyDown(
        keyCode: Int,
        e: KeyEvent?,
        session: TerminalSession?
    ): Boolean {
        return false
    }

    override fun onKeyUp(
        keyCode: Int,
        e: KeyEvent?
    ): Boolean {
        return false
    }

    override fun onLongPress(
        event: MotionEvent?
    ): Boolean {
        return false
    }

    override fun readControlKey(): Boolean {
        return ctrlPressed
    }

    override fun readAltKey(): Boolean {
        return altPressed
    }

    override fun readShiftKey(): Boolean {
        return false
    }

    override fun readFnKey(): Boolean {
        return false
    }

    override fun onCodePoint(
        codePoint: Int,
        ctrlDown: Boolean,
        session: TerminalSession?
    ): Boolean {
        return false
    }

    override fun onEmulatorSet() {
        terminalView.post {
            terminalView.invalidate()
        }
    }
}
