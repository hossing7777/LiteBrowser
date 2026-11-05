package com.litebrowser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
    private lateinit var btnToggleView: Button
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDataUsage: TextView
    private lateinit var webView: WebView
    private lateinit var scrollLogs: ScrollView
    private lateinit var frameWebView: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutProgress: LinearLayout
    private lateinit var pbTaskProgress: ProgressBar
    private lateinit var tvProgressPercent: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isPageLoaded = false
    private var isLogsHidden = false
    private val logBuilder = SpannableStringBuilder()
    private var lastStatusLine = ""

    private var startRx = 0L
    private var startTx = 0L
    private var totalRx = 0L
    private var totalTx = 0L

    private val agentCodeFile by lazy { File(filesDir, "home/agent_code.txt") }
    private val agentMemoryFile by lazy { File(filesDir, "home/agent_memory.txt") }

    private val recentSteps = mutableListOf<Pair<String, String>>()
    private val aiNotes = mutableListOf<String>()
    private var originalTask: String = ""
    private var lastExecutedCommand: String = ""
    private var agentSession: TerminalSession? = null

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
        btnToggleView = findViewById(R.id.btn_toggle_view)
        tvLog = findViewById(R.id.tv_agent_log)
        tvStatus = findViewById(R.id.tv_agent_status)
        tvDataUsage = findViewById(R.id.tv_data_usage)
        webView = findViewById(R.id.agent_webview)
        scrollLogs = findViewById(R.id.scroll_logs)
        frameWebView = findViewById(R.id.frame_webview)
        progressBar = findViewById(R.id.agent_progress)
        layoutProgress = findViewById(R.id.layout_progress)
        pbTaskProgress = findViewById(R.id.pb_task_progress)
        tvProgressPercent = findViewById(R.id.tv_progress_percent)

        requestNotificationPermission()

        if (intent?.getBooleanExtra("START_UPGRADE", false) == true) {
            val autoPrompt = "Read /root/Download/Local_AI_Models/tools_keywords.txt " +
                "and implement the tools/keywords listed there. " +
                "Each tool goes to /root/Download/Local_AI_Models/Tools_app/ToolName_YYYYMMDD/ " +
                "with info.txt (3 lines: Persian, English, TESTING then READY after verification). " +
                "Test each tool end-to-end with sh script.sh before setting READY."
            etPrompt.setText(autoPrompt)
            handler.postDelayed({
                if (isPageLoaded && !isRunning) {
                    startAgent(autoPrompt)
                }
            }, 3000)
        }


        totalRx = TrafficStats.getUidRxBytes(Process.myUid())
        totalTx = TrafficStats.getUidTxBytes(Process.myUid())
        if (totalRx == TrafficStats.UNSUPPORTED.toLong()) totalRx = 0L
        if (totalTx == TrafficStats.UNSUPPORTED.toLong()) totalTx = 0L

        handler.postDelayed({
            setupWebView()
            webView.loadUrl("https://chat.deepseek.com/")
        }, 800)

        btnToggleView.setOnClickListener {
            isLogsHidden = !isLogsHidden
            if (isLogsHidden) {
                scrollLogs.visibility = View.GONE
                frameWebView.layoutParams.height = 0
                (frameWebView.layoutParams as LinearLayout.LayoutParams).weight = 2.5f
                btnToggleView.text = "📜 نمایش لاگ"
            } else {
                scrollLogs.visibility = View.VISIBLE
                (scrollLogs.layoutParams as LinearLayout.LayoutParams).weight = 1f
                (frameWebView.layoutParams as LinearLayout.LayoutParams).weight = 1.5f
                btnToggleView.text = "🖥️ بزرگ‌نمایی"
            }
            scrollLogs.requestLayout()
            frameWebView.requestLayout()
        }

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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun stopAgent() {
        if (isRunning) {
            isRunning = false
            handler.removeCallbacksAndMessages(null)
            val session = getAgentSession()
            if (session != null) session.write("\u0003")
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

    private fun updateDataUsage() {
        val currentRx = TrafficStats.getUidRxBytes(Process.myUid())
        val currentTx = TrafficStats.getUidTxBytes(Process.myUid())
        if (currentRx != TrafficStats.UNSUPPORTED.toLong() && currentTx != TrafficStats.UNSUPPORTED.toLong()) {
            val deltaRx = currentRx - startRx
            val deltaTx = currentTx - startTx
            val totalMB = (deltaRx + deltaTx) / (1024.0 * 1024.0)
            runOnUiThread {
                tvDataUsage.text = String.format("Data used: %.2f MB", totalMB)
            }
        }
    }

    private fun ensureTerminalSession() {
        val existing = agentSession?.takeIf { it.isRunning }
            ?: TerminalManager.sessions.lastOrNull()?.takeIf { it.isRunning }
        if (existing != null) {
            agentSession = existing
            Logger.i("Agent", "Reusing session pid=" + existing.getPid())
            return
        }
        (application as LiteApp).writeDnsConfig()
        val session = SessionFactory.createSession(this, 80, 24)
        if (session != null) {
            TerminalManager.addSession(session)
            agentSession = session
            Logger.i("Agent", "Created new session pid=" + session.getPid())
        } else {
            Logger.e("Agent", "Failed to create terminal session")
            log("ERROR: Failed to create terminal session (Alpine not installed?)", Color.RED)
        }
    }

    private fun getAgentSession(): TerminalSession? {
        return agentSession?.takeIf { it.isRunning }
            ?: TerminalManager.sessions.lastOrNull()?.takeIf { it.isRunning }
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

        startRx = TrafficStats.getUidRxBytes(Process.myUid())
        startTx = TrafficStats.getUidTxBytes(Process.myUid())
        if (startRx == TrafficStats.UNSUPPORTED.toLong()) startRx = 0L
        if (startTx == TrafficStats.UNSUPPORTED.toLong()) startTx = 0L
        updateDataUsage()

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
You are an expert Alpine Linux terminal bot AND an autonomous Android developer.
Your ONLY job is to output ONE bash command, wrapped in a ```bash block.
Do NOT explain anything outside the code block, except for the tags below.
You run as root inside an Alpine PRoot on an Android phone.

[OUTPUT TAGS]
After the command, on a new line OUTSIDE the code block, you may add:
  [PROGRESS: X%]       - current progress 0-100
  [NOTE: any fact]     - a fact you learned that should persist in memory
When the whole task is fully done, output ONLY the text TASK_COMPLETE (no code block).

[ENVIRONMENT]
- Working directory: /root
- Phone storage: /root/Download  (host: /storage/emulated/0/Download)
- AI cache dir:  /root/Download/Local_AI_Models/Library_Cache
- AI tools dir:  /root/Download/Local_AI_Models/Tools_app
- App source:    /root/Download/LiteBrowser-work
- Available: apk (Alpine), python3, bash, git, coreutils, tar, unzip

[SMART PACKAGE INSTALL]
Use the APK cache to avoid redownloading packages across sessions:
  PACKAGE="pkgname"
  CACHE_DIR="/root/Download/Local_AI_Models/Library_Cache/apk"
  mkdir -p "${'$'}CACHE_DIR"
  if ls "${'$'}CACHE_DIR"/${'$'}PACKAGE"-*.apk 1>/dev/null 2>&1; then
      apk add --no-cache --allow-untrusted "${'$'}CACHE_DIR"/${'$'}PACKAGE"-*.apk || apk add --no-cache "${'$'}PACKAGE"
  else
      apk fetch --recursive -o "${'$'}CACHE_DIR"" "${'$'}PACKAGE"
      apk add --no-cache --allow-untrusted "${'$'}CACHE_DIR""/*.apk || apk add --no-cache "${'$'}PACKAGE"
  fi

[TOOLS - lightweight, no APK rebuild needed]
Create standalone tools under:
  /root/Download/Local_AI_Models/Tools_app/ToolName_YYYYMMDD/
Each tool MUST have info.txt with EXACTLY 3 lines:
  Line 1: Persian description
  Line 2: English description
  Line 3: STATUS ("TESTING" first, then "READY" after you verify it works)
Test the tool end-to-end before setting STATUS=READY.

[APP SOURCE UPGRADE - rebuilds the APK]
Modifying LiteBrowser's own Kotlin source requires a GitHub Actions rebuild.
Workflow:
  1. cd /root/Download/LiteBrowser-work
  2. Edit files ONLY via Python scripts or base64 to avoid shell truncation.
     NEVER use "cat << EOF" on .kt files.
     NEVER use emojis inside Kotlin string literals (encoding breaks).
  3. Deploy:
       git add -A
       git commit -m "agent: <short description>"
       git push origin main
  4. GitHub Actions (.github/workflows/build.yml) automatically builds the new APK.
  5. The USER downloads the APK from GitHub Actions and installs it manually.
     Do NOT expect live changes in this session - the change is live on next install.

[SELF-UPGRADE PRIORITIES]
1. Read /root/Download/Local_AI_Models/tools_keywords.txt (if it exists) - implement those ideas.
2. Fulfill the user's request (see [TASK] below).
3. Improve yourself when you spot a real, verified improvement.

[FUSE MOUNT WARNING]
Files under /root/Download/ are on a FUSE mount (Android storage).
NEVER use "./script.sh" to run a tool - it will fail with "Permission denied" even after chmod +x.
ALWAYS use "sh /path/to/script.sh" or "/bin/sh /path/to/script.sh" instead.
NEVER chmod inside /root/Download/ - it silently fails on FUSE.

[CRITICAL RULES]
- Output exactly ONE bash block per response.
- Never write to /tmp - use /root/tmp or /root/Download.
- Alpine uses "apk" (not apt-get).
- If a build fails later, the failure shows in GitHub Actions logs - you do not debug builds locally.

[TASK]
${prompt}
""".trimIndent()

        sendToAI(systemPrompt)
        handler.postDelayed({ checkAiResponse() }, 2000)
    }

    private fun sendToAI(text: String) {
        log("📤 Sending prompt to AI...", Color.BLUE)
        updateDataUsage()
        val base64Prompt = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        
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
                            if (document.body.innerText.includes("Messages are too frequent") || document.body.innerText.includes("پیام‌ها خیلی زیاد")) {
                                return; 
                            }
                            ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true, cancelable: true }));
                            var buttons = document.querySelectorAll('button, div[role="button"]');
                            for (var i = buttons.length - 1; i >= 0; i--) {
                                var btn = buttons[i];
                                if (btn.innerHTML.includes('svg') && btn.offsetWidth > 0) {
                                    var rect = btn.getBoundingClientRect();
                                    var taRect = ta.getBoundingClientRect();
                                    if (rect.left > taRect.left + (taRect.width / 2) && rect.top > taRect.top) {
                                        btn.removeAttribute('disabled');
                                        btn.click();
                                        break;
                                    }
                                }
                            }
                            setTimeout(function() {
                                var currentText = ta.value || ta.innerText || "";
                                if (currentText.trim().length > 0 && clickAttempts < maxAttempts) {
                                    trySend();
                                }
                            }, 2000);
                        }
                        setTimeout(trySend, 3000);
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
        
        if (aiCheckAttempts % 5 == 0) updateDataUsage()

        if (aiCheckAttempts > 1200) { 
            log("❌ Error: Total timeout waiting for AI response.", Color.RED)
            updateStatus(""); stopAgent(); return
        }

        val js = """
            (function() {
                try {
                    // Check for CAPTCHA
                    if (document.body.innerText.includes("Click on all the things") || document.body.innerText.includes("CAPTCHA") || document.body.innerText.includes("تصاویر")) {
                        return JSON.stringify({ captcha: true });
                    }

                    window.scrollTo(0, document.body.scrollHeight);
                    if (document.body.innerText.includes("Messages are too frequent") || document.body.innerText.includes("پیام‌ها خیلی زیاد")) {
                        return JSON.stringify({ rateLimited: true });
                    }
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
                handler.postDelayed({ checkAiResponse() }, 1000); return@evaluateJavascript
            }
            val cleanJson = result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            try {
                val jsonObj = org.json.JSONObject(cleanJson)
                
                if (jsonObj.optBoolean("captcha", false)) {
                    log("\n⚠️ CAPTCHA Detected! Please solve the image test in the WebView below to continue...", Color.RED)
                    updateStatus("Waiting for CAPTCHA...")
                    // Wait 15 seconds before checking again (gives user time to solve)
                    handler.postDelayed({ checkAiResponse() }, 15000)
                    return@evaluateJavascript
                }

                if (jsonObj.optBoolean("rateLimited", false)) {
                    log("\n⚠️ Rate Limit hit! Waiting 30 seconds before auto-resuming...", Color.YELLOW)
                    handler.postDelayed({ 
                        if(isRunning) {
                            val retryJs = "document.querySelector('button[type=\"submit\"]')?.click();"
                            webView.evaluateJavascript(retryJs, null)
                            handler.postDelayed({ checkAiResponse() }, 5000)
                        }
                    }, 30000)
                    return@evaluateJavascript
                }

                val hasCode = jsonObj.getBoolean("hasCode")
                val isGenerating = jsonObj.getBoolean("isGenerating")
                val taskComplete = jsonObj.optBoolean("taskComplete", false)
                val alreadyProcessed = jsonObj.optBoolean("alreadyProcessed", false)
                val progress = jsonObj.optInt("progress", -1)
                val aiText = jsonObj.optString("aiText", "")

                if (taskComplete) {
                    log("\n🎉 TASK COMPLETE! The agent has finished the task successfully.", Color.GREEN)
                    updateStatus(""); updateProgress(100)
                    saveMessageToChat("ai", "TASK_COMPLETE")
                    isRunning = false; toggleButtons(running = false)
                    stopService(Intent(this, AgentForegroundService::class.java))
                    updateDataUsage()
                    return@evaluateJavascript
                }
                if (progress >= 0) updateProgress(progress)
                if (alreadyProcessed) { handler.postDelayed({ checkAiResponse() }, 1000); return@evaluateJavascript }
                if (!hasCode || isGenerating) { handler.postDelayed({ checkAiResponse() }, 1000); return@evaluateJavascript }

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
                handler.postDelayed({ checkAiResponse() }, 1000)
            }
        }
    }

    private fun processAiResponse(command: String) {
        log("\n💻 Running Command:\n$command\n", Color.parseColor("#FF9800"))
        lastExecutedCommand = command

        val session = getAgentSession()
        if (session != null) {
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
        val session = getAgentSession()
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
                        
                        log("⏳ Waiting 10s to avoid rate limit...", Color.GRAY)
                        handler.postDelayed({
                            if (isRunning) sendToAI(nextPrompt)
                        }, 10000)
                        
                        handler.postDelayed({ checkAiResponse() }, 15000)
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
