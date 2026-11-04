package com.litebrowser

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import java.io.File
import kotlin.concurrent.thread

class AgentActivity : Activity() {

    private lateinit var etPrompt: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnHistory: Button
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutProgress: LinearLayout
    private lateinit var pbTaskProgress: ProgressBar
    private lateinit var tvProgressPercent: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isPageLoaded = false
    private val logBuilder = SpannableStringBuilder()
    private var lastStatusLine = ""

    private val agentCodeFile by lazy { File(filesDir, "home/agent_code.txt") }
    private val agentMemoryFile by lazy { File(filesDir, "home/agent_memory.txt") }

    // MEMORY SYSTEM
    private val recentSteps = mutableListOf<Pair<String, String>>()
    private val aiNotes = mutableListOf<String>()
    private var originalTask: String = ""
    private var lastExecutedCommand: String = ""

    private var currentChatFolder: File? = null
    private val currentChatMessages = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("Agent", "AgentActivity onCreate started")
        setContentView(R.layout.activity_agent)

        etPrompt = findViewById(R.id.et_agent_prompt)
        btnStart = findViewById(R.id.btn_agent_start)
        btnStop = findViewById(R.id.btn_agent_stop)
        btnHistory = findViewById(R.id.btn_agent_history)
        tvLog = findViewById(R.id.tv_agent_log)
        tvStatus = findViewById(R.id.tv_agent_status)
        webView = findViewById(R.id.agent_webview)
        progressBar = findViewById(R.id.agent_progress)
        layoutProgress = findViewById(R.id.layout_progress)
        pbTaskProgress = findViewById(R.id.pb_task_progress)
        tvProgressPercent = findViewById(R.id.tv_progress_percent)

        handler.postDelayed({
            setupWebView()
            webView.loadUrl("https://chat.deepseek.com/")
        }, 800)

        btnHistory.setOnClickListener {
            startActivity(Intent(this, ChatHistoryActivity::class.java))
        }

        btnStart.setOnClickListener {
            if (isRunning) {
                Toast.makeText(this, "ایجنت در حال اجراست...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isPageLoaded) {
                Toast.makeText(this, "لطفاً صبر کنید تا صفحه دیپ‌سیک لود شود...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prompt = etPrompt.text.toString().trim()
            if (prompt.isNotEmpty()) startAgent(prompt)
            else Toast.makeText(this, "لطفا یک درخواست وارد کنید", Toast.LENGTH_SHORT).show()
        }

        btnStop.setOnClickListener { stopAgent() }
    }

    private fun stopAgent() {
        if (isRunning) {
            isRunning = false
            handler.removeCallbacksAndMessages(null)
            val session = TerminalManager.sessions.firstOrNull()
            if (session != null && session.isRunning) session.write("\u0003")
            log("\n🛑 Agent stopped by user.", Color.RED)
            updateStatus("")
            updateProgress(0)
            toggleButtons(running = false)
            stopService(Intent(this, AgentForegroundService::class.java))
        }
    }

    private fun toggleButtons(running: Boolean) {
        runOnUiThread {
            if (running) {
                btnStart.isEnabled = false; btnStop.visibility = View.VISIBLE; etPrompt.isEnabled = false
            } else {
                btnStart.isEnabled = true; btnStop.visibility = View.GONE; etPrompt.isEnabled = true
            }
        }
    }

    private fun ensureTerminalSession() {
        (application as LiteApp).writeDnsConfig()

        if (TerminalManager.sessions.isEmpty() || TerminalManager.sessions.firstOrNull()?.isRunning == false) {
            Logger.i("Agent", "Creating new terminal session")
            val app = application as LiteApp
            val prefixDir = File(app.filesDir, "usr")
            val homeDir = File(app.filesDir, "home").also { it.mkdirs() }
            val tmpDir = File(app.cacheDir, "proot-tmp").also { it.mkdirs() }
            val nativeDir = app.applicationInfo.nativeLibraryDir
            val resolvConfHost = File(app.filesDir, "etc/resolv.conf")

            val prootBin = File(nativeDir, "libproot.so")
            val prootLoader = File(nativeDir, "libproot-loader.so")
            val alpineRoot = File(prefixDir, "var/lib/pr/installed-rootfs/alpine")

            val envVars = arrayOf(
                "PATH=${nativeDir}:${prefixDir.absolutePath}/bin:/system/bin:/system/xbin",
                "HOME=${homeDir.absolutePath}", "PREFIX=${prefixDir.absolutePath}",
                "LD_LIBRARY_PATH=${nativeDir}:${prefixDir.absolutePath}/lib",
                "APP_PREFIX=${prefixDir.absolutePath}", "APP_HOME=${homeDir.absolutePath}",
                "APP_PACKAGE=${app.packageName}", "PROOT_LOADER=${prootLoader.absolutePath}",
                "PROOT_NO_SECCOMP=1", "PROOT_TMP_DIR=${tmpDir.absolutePath}",
                "TMPDIR=${tmpDir.absolutePath}", "TERM=xterm-256color",
                "LANG=C.UTF-8", "LC_ALL=C.UTF-8"
            )

            val args = arrayOf(
                prootBin.absolutePath, "--link2symlink", "-0",
                "-r", alpineRoot.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "${homeDir.absolutePath}:/root",
                "-b", "${resolvConfHost.absolutePath}:/etc/resolv.conf",
                "-b", "${prefixDir.absolutePath}/bin:/usr/local/bin",
                "-b", "/storage/emulated/0/Download:/root/Download",
                "-b", "/storage/emulated/0/Download/Local_AI_Models/Library_Cache:/root/Local_AI_Models/Library_Cache",
                "-w", "/root", "/bin/sh", "-l"
            )

            val session = TerminalSession(prootBin.absolutePath, homeDir.absolutePath, args, envVars, 2000, TerminalManager)
            session.updateSize(80, 24)
            TerminalManager.addSession(session)
        }
    }

    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(WebAppInterface(this, webView), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                runOnUiThread { progressBar.visibility = View.VISIBLE }
                isPageLoaded = false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                runOnUiThread { progressBar.visibility = View.GONE }
                isPageLoaded = true
                log("✅ DeepSeek page loaded. Ready to start.\n", Color.GREEN)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    private fun log(msg: String, color: Int = Color.WHITE) {
        runOnUiThread {
            val start = logBuilder.length
            logBuilder.append(msg).append("\n")
            logBuilder.setSpan(ForegroundColorSpan(color), start, logBuilder.length, 0)
            if (logBuilder.length > 50000) logBuilder.delete(0, 20000)
            tvLog.text = logBuilder
            try {
                val sv = tvLog.parent as? ScrollView
                sv?.post { sv.fullScroll(View.FOCUS_DOWN) }
            } catch (e: Exception) {}
        }
    }

    private fun updateStatus(status: String) {
        if (status == lastStatusLine) return
        lastStatusLine = status
        runOnUiThread {
            if (status.isEmpty()) tvStatus.visibility = View.GONE
            else {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "⏳ $status"
                tvStatus.setTextColor(if (status.contains("[Local Cache]")) Color.parseColor("#4CAF50") else Color.parseColor("#2196F3"))
            }
        }
    }

    private fun updateProgress(percent: Int) {
        runOnUiThread {
            layoutProgress.visibility = View.VISIBLE
            pbTaskProgress.progress = percent
            tvProgressPercent.text = "$percent%"
            tvProgressPercent.setTextColor(if (percent == 100) Color.parseColor("#4CAF50") else Color.parseColor("#2196F3"))
        }
    }

    private fun saveMessageToChat(role: String, content: String) {
        currentChatMessages.add(ChatMessage(role, content))
        currentChatFolder?.let { ChatManager.saveChatHistory(it, currentChatMessages) }
    }

    private fun buildMemoryContext(): String {
        if (recentSteps.isEmpty() && aiNotes.isEmpty() && originalTask.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n[MEMORY - PREVIOUS PROGRESS]\n")
        if (originalTask.isNotEmpty()) sb.append("Original Task: $originalTask\n\n")
        if (aiNotes.isNotEmpty()) {
            sb.append("[Key Notes you learned earlier]:\n")
            aiNotes.takeLast(10).forEach { sb.append("- $it\n") }
            sb.append("\n")
        }
        if (recentSteps.isNotEmpty()) {
            sb.append("[Recent Steps Summary]:\n")
            recentSteps.takeLast(5).forEachIndexed { i, (cmd, out) ->
                sb.append("${i + 1}. Command: ${cmd.take(200).replace('\n', ' ')}\n")
                sb.append("   Output: ${out.take(300).replace('\n', ' ')}\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun saveMemoryToFile() {
        try {
            val sb = StringBuilder()
            sb.append("TASK: $originalTask\n")
            aiNotes.forEach { sb.append("NOTE: $it\n") }
            recentSteps.forEach { (cmd, out) ->
                sb.append("STEP: ${cmd.replace('\n', ' ').take(500)}\n")
                sb.append("OUTPUT: ${out.replace('\n', ' ').take(1000)}\n")
            }
            agentMemoryFile.writeText(sb.toString())
        } catch (e: Exception) { Logger.e("Agent", "saveMemory error", e) }
    }

    private fun extractAiNotes(text: String) {
        try {
            val regex = Regex("\\[NOTE:\\s*([^\\]]+)\\]")
            regex.findAll(text).forEach { match ->
                val note = match.groupValues[1].trim()
                if (note.isNotEmpty() && !aiNotes.contains(note)) {
                    aiNotes.add(note)
                    if (aiNotes.size > 20) aiNotes.removeAt(0)
                    Logger.i("Agent", "AI note saved: $note")
                }
            }
        } catch (e: Exception) {}
    }

    private fun startAgent(prompt: String) {
        isRunning = true
        toggleButtons(running = true)
        logBuilder.clear()
        updateStatus("")
        updateProgress(0)

        startForegroundService(Intent(this, AgentForegroundService::class.java))

        originalTask = prompt
        recentSteps.clear()
        aiNotes.clear()
        lastExecutedCommand = ""
        agentMemoryFile.delete()

        log("🤖 Starting Agent with task: $prompt\n", Color.CYAN)
        currentChatFolder = ChatManager.createNewChat(prompt)
        currentChatMessages.clear()
        saveMessageToChat("user", prompt)
        ensureTerminalSession()

        val systemPrompt = """
            [INSTRUCTIONS]
            You are an Alpine Linux terminal bot. Your ONLY job is to output a single bash command wrapped in ```bash and ``` blocks.
            Do NOT explain anything. Do NOT use markdown outside the code block.
            You are running as root.

            [PROGRESS REPORTING]
            After each major step, on a new line OUTSIDE the code block, write:
            [PROGRESS: X%]

            [PERSISTENT MEMORY]
            For long complex tasks, if you learn a critical fact, you may include it OUTSIDE the code block like:
            [NOTE: your important fact here]
            These notes are saved and will be shown to you in future prompts. Use them wisely.

            [SMART PACKAGE INSTALLATION]
            You have a local cache at /root/Local_AI_Models/Library_Cache/
            If you need to install an 'apk' package, use this logic:
            PACKAGE="package_name"
            CACHE_DIR="/root/Local_AI_Models/Library_Cache/apk"
            if ls ${'$'}CACHE_DIR/${'$'}PACKAGE-*.apk 1> /dev/null 2>&1; then
                apk add --no-cache --allow-untrusted ${'$'}CACHE_DIR/${'$'}PACKAGE-*.apk || { apk add --no-cache ${'$'}PACKAGE; cp /var/cache/apk/*${'$'}PACKAGE*.apk ${'$'}CACHE_DIR/ 2>/dev/null; }
            else
                apk add --no-cache ${'$'}PACKAGE
                cp /var/cache/apk/*${'$'}PACKAGE*.apk ${'$'}CACHE_DIR/ 2>/dev/null || true
            fi

            [TASK]
            $prompt
        """.trimIndent()

        sendToAI(systemPrompt)
        handler.postDelayed({ checkAiResponse() }, 2000)
    }

    private fun sendToAI(text: String) {
        log("📤 Sending prompt to AI...", Color.BLUE)
        val base64Prompt = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        
        // AUTO-CLICKER: If text is not sent, retry clicking the send button automatically
        val js = """
            (function() {
                try {
                    var text = decodeURIComponent(escape(atob("$base64Prompt")));
                    var ta = document.querySelector('textarea') || document.querySelector('div[contenteditable="true"]');
                    if (ta) {
                        ta.focus();
                        if (ta.tagName === 'TEXTAREA') {
                            var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                            nativeSetter.call(ta, text);
                        } else { ta.innerText = text; }
                        ta.dispatchEvent(new Event('input', { bubbles: true }));
                        ta.dispatchEvent(new Event('change', { bubbles: true }));
                        
                        var clickAttempts = 0;
                        var maxAttempts = 3;
                        
                        function trySend() {
                            clickAttempts++;
                            // Dispatch Enter key
                            ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true, cancelable: true }));
                            
                            // Find and click the orange send button
                            var buttons = document.querySelectorAll('button, div[role="button"]');
                            for (var i = buttons.length - 1; i >= 0; i--) {
                                var btn = buttons[i];
                                if (btn.innerHTML.includes('svg') && btn.offsetWidth > 0) {
                                    var rect = btn.getBoundingClientRect();
                                    var taRect = ta.getBoundingClientRect();
                                    if (rect.left > taRect.left + (taRect.width / 2) && rect.top > taRect.top) {
                                        btn.removeAttribute('disabled'); // Force enable just in case
                                        btn.click();
                                        break;
                                    }
                                }
                            }
                            
                            // If textarea is still not empty after 1.5s, try again
                            setTimeout(function() {
                                var currentText = ta.value || ta.innerText || "";
                                if (currentText.trim().length > 0 && clickAttempts < maxAttempts) {
                                    trySend();
                                }
                            }, 1500);
                        }
                        
                        setTimeout(trySend, 500);
                    }
                } catch(e) { AndroidBridge.onAgentError("JS Exception: " + e.message); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private var aiCheckAttempts = 0
    private var lastAiText = ""

    private fun checkAiResponse() {
        if (!isRunning) return
        aiCheckAttempts++
        // Increased timeout to 10 minutes (1200 * 500ms)
        if (aiCheckAttempts > 1200) {
            log("❌ Error: Total timeout waiting for AI response.", Color.RED)
            updateStatus(""); stopAgent(); return
        }

        val js = """
            (function() {
                try {
                    window.scrollTo(0, document.body.scrollHeight);
                    var markdowns = document.querySelectorAll('.ds-markdown');
                    if (markdowns.length === 0) return JSON.stringify({ isGenerating: true, hasCode: false, taskComplete: false, alreadyProcessed: false, progress: -1, aiText: "" });
                    var lastMd = markdowns[markdowns.length - 1];
                    if (lastMd.getAttribute('data-processed') === 'true') return JSON.stringify({ isGenerating: false, hasCode: false, taskComplete: false, alreadyProcessed: true, progress: -1, aiText: "" });
                    var text = lastMd.innerText || lastMd.textContent || "";
                    var progressMatch = text.match(/\[PROGRESS:\s*(\d+)%\]/i);
                    var progressVal = progressMatch ? parseInt(progressMatch[1], 10) : -1;
                    var textClean = text.replace(/\[PROGRESS:\s*\d+%\]/gi, "").trim();
                    if (textClean.includes("TASK_COMPLETE")) {
                        lastMd.setAttribute('data-processed', 'true');
                        return JSON.stringify({ isGenerating: false, hasCode: false, taskComplete: true, alreadyProcessed: false, progress: 100, aiText: textClean });
                    }
                    var codeText = "";
                    var hasCode = false;
                    var pre = lastMd.querySelector('pre');
                    if (pre) {
                        codeText = pre.innerText || pre.textContent || "";
                        codeText = codeText.replace(/^Copy\s*\n?/i, "").replace(/^Download\s*\n?/i, "").replace(/^bash\s*\n?/i, "").trim();
                        hasCode = true;
                    } else {
                        var match = textClean.match(/```(?:bash|sh|shell)?\s*([\s\S]*?)```/);
                        if (match) { codeText = match[1].trim(); hasCode = true; }
                    }
                    var isGenerating = false;
                    if (textClean.endsWith("●") || textClean.endsWith("▍") || textClean.endsWith("_")) isGenerating = true;
                    if (hasCode && !isGenerating) {
                        lastMd.setAttribute('data-processed', 'true');
                        AndroidBridge.saveAgentCode(codeText);
                    }
                    return JSON.stringify({ isGenerating: isGenerating, hasCode: hasCode, taskComplete: false, alreadyProcessed: false, progress: progressVal, aiText: textClean });
                } catch(e) { return JSON.stringify({ isGenerating: true, hasCode: false, taskComplete: false, alreadyProcessed: false, jsError: String(e), progress: -1, aiText: "" }); }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (!isRunning) return@evaluateJavascript
            if (result == null || result == "null" || result == "\"\"") {
                handler.postDelayed({ checkAiResponse() }, 500); return@evaluateJavascript
            }
            val cleanJson = result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            try {
                val jsonObj = org.json.JSONObject(cleanJson)
                val hasCode = jsonObj.getBoolean("hasCode")
                val isGenerating = jsonObj.getBoolean("isGenerating")
                val taskComplete = jsonObj.optBoolean("taskComplete", false)
                val alreadyProcessed = jsonObj.optBoolean("alreadyProcessed", false)
                val progress = jsonObj.optInt("progress", -1)
                val aiText = jsonObj.optString("aiText", "")

                // AUTO-NUDGE: If stuck for a long time without progress, try re-sending the last prompt or clicking the send button
                if (aiCheckAttempts > 120 && aiText == lastAiText && !hasCode && !isGenerating) {
                    log("⏳ AI seems stuck. Trying to auto-resume...", Color.YELLOW)
                    // JS to click the send/regenerate button if it exists
                    val nudgeJs = """
                        (function() {
                            var buttons = document.querySelectorAll('button');
                            for (var i = 0; i < buttons.length; i++) {
                                if (buttons[i].innerText.includes('Regenerate') || buttons[i].innerText.includes('Continue')) {
                                    buttons[i].click(); return;
                                }
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(nudgeJs, null)
                    aiCheckAttempts = 0 // reset attempts so it doesn't spam
                }
                if (aiText.isNotEmpty()) lastAiText = aiText

                if (taskComplete) {
                    log("\n🎉 TASK COMPLETE! The agent has finished the task successfully.", Color.GREEN)
                    updateStatus(""); updateProgress(100)
                    saveMessageToChat("ai", "TASK_COMPLETE")
                    isRunning = false; toggleButtons(running = false)
                    stopService(Intent(this, AgentForegroundService::class.java))
                    return@evaluateJavascript
                }
                if (progress >= 0) updateProgress(progress)
                if (alreadyProcessed) { handler.postDelayed({ checkAiResponse() }, 500); return@evaluateJavascript }
                if (!hasCode || isGenerating) { handler.postDelayed({ checkAiResponse() }, 500); return@evaluateJavascript }

                if (aiText.isNotEmpty()) extractAiNotes(aiText)

                if (agentCodeFile.exists()) {
                    val text = agentCodeFile.readText().trim()
                    agentCodeFile.delete()
                    if (text.isNotEmpty()) {
                        log("📥 AI response received. (${text.length} chars)", Color.GREEN)
                        saveMessageToChat("ai", text)
                        aiCheckAttempts = 0
                        processAiResponse(text)
                    }
                }
            } catch (e: Exception) {
                Logger.e("Agent", "JSON parse error: ${e.message}")
                handler.postDelayed({ checkAiResponse() }, 500)
            }
        }
    }

    private fun processAiResponse(command: String) {
        log("\n💻 Running Command:\n$command\n", Color.parseColor("#FF9800"))
        lastExecutedCommand = command

        val session = TerminalManager.sessions.firstOrNull()
        if (session != null && session.isRunning) {
            val scriptFile = File(filesDir, "home/run_agent.sh")
            scriptFile.writeText(command.replace("\r", "").trim() + "\n")
            val startMarker = "___AGENT_START___"
            val endMarker = "___AGENT_END___"
            session.write("echo \"$startMarker\"; sh /root/run_agent.sh < /dev/null; echo \"$endMarker\"\r")
            log("⏳ Executing...", Color.GRAY)
            updateStatus("Starting execution...")
            handler.postDelayed({ readTerminalOutput(startMarker, endMarker, 0) }, 1000)
        } else {
            log("❌ Error: No terminal session.", Color.RED)
            stopAgent()
        }
    }

    private fun readTerminalOutput(startMarker: String, endMarker: String, attempts: Int) {
        if (!isRunning) return
        if (attempts > 600) {
            log("❌ Error: Command execution timed out.", Color.RED)
            updateStatus(""); stopAgent(); return
        }
        val session = TerminalManager.sessions.firstOrNull()
        if (session == null) {
            handler.postDelayed({ readTerminalOutput(startMarker, endMarker, attempts + 1) }, 500); return
        }
        thread {
            val screenText = session.emulator?.screen?.getTranscriptText() ?: ""
            if (screenText.contains(endMarker)) {
                val startIndex = screenText.lastIndexOf(startMarker)
                val endIndex = screenText.lastIndexOf(endMarker)
                if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                    val rawOutput = screenText.substring(startIndex + startMarker.length, endIndex)
                    val cleanOutput = rawOutput.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
                        .lines().filterNot { it.contains("sh /root/run_agent.sh") || it.contains("echo \"___AGENT") }
                        .joinToString("\n").trim()
                    var finalOutput = if (cleanOutput.isEmpty()) "Command executed successfully (no output)." else cleanOutput
                    if (finalOutput.length > 5000) finalOutput = finalOutput.take(5000) + "\n... (output truncated)"

                    if (lastExecutedCommand.isNotEmpty()) {
                        recentSteps.add(lastExecutedCommand to finalOutput)
                        if (recentSteps.size > 8) recentSteps.removeAt(0)
                        saveMemoryToFile()
                    }

                    runOnUiThread {
                        if (!isRunning) return@runOnUiThread
                        updateStatus("")
                        log("\n📄 Output:\n$finalOutput\n", Color.YELLOW)
                        saveMessageToChat("terminal_output", finalOutput)

                        val nextPrompt = buildMemoryContext() +
                            "\n[COMMAND OUTPUT TO ANALYZE]\n$finalOutput\n\nWhat is the next command? (Or reply TASK_COMPLETE if done)"
                        sendToAI(nextPrompt)
                        handler.postDelayed({ checkAiResponse() }, 2000)
                    }
                    return@thread
                }
            } else {
                val lines = screenText.trim().lines()
                if (lines.isNotEmpty()) {
                    val lastLine = lines.last().replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").trim()
                    if (lastLine.isNotEmpty() && lastLine.length < 100) runOnUiThread { updateStatus(lastLine) }
                }
            }
            handler.postDelayed({ readTerminalOutput(startMarker, endMarker, attempts + 1) }, 500)
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
