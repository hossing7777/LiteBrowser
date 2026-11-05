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
    private lateinit var etApiKey: EditText
    private lateinit var etApiModel: EditText
    private lateinit var layoutApiSettings: LinearLayout
    private lateinit var btnSaveApi: Button
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
        etApiKey = findViewById(R.id.et_api_key)
        etApiModel = findViewById(R.id.et_api_model)
        layoutApiSettings = findViewById(R.id.layout_api_settings)
        btnSaveApi = findViewById(R.id.btn_save_api)
        
        btnSaveApi.setOnClickListener {
            val prefs = getSharedPreferences("LitePrefs", MODE_PRIVATE)
            prefs.edit()
                .putString("api_key", etApiKey.text.toString().trim())
                .putString("api_model", etApiModel.text.toString().trim())
                .apply()
            Toast.makeText(this, "API settings saved", Toast.LENGTH_SHORT).show()
        }
        layoutApiSettings.visibility = View.VISIBLE
        
        // لود کردن کلید ذخیره شده
        val prefs = getSharedPreferences("LitePrefs", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", "ng-8a69afcc9a542af2a0d445a31175026f"))
        etApiModel.setText(prefs.getString("api_model", "thinkingmachines/inkling:free"))
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
                if (!isRunning) {
                    startAgent(autoPrompt)
                }
            }, 1000)
        }


        totalRx = TrafficStats.getUidRxBytes(Process.myUid())
        totalTx = TrafficStats.getUidTxBytes(Process.myUid())
        if (totalRx == TrafficStats.UNSUPPORTED.toLong()) totalRx = 0L
        if (totalTx == TrafficStats.UNSUPPORTED.toLong()) totalTx = 0L

        handler.postDelayed({
            setupWebView()
            // // webView.loadUrl("https://chat.deepseek.com/") // Disabled DeepSeek // COMMENTED OUT: DeepSeek WebView is disabled
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

    
    private fun sendToApi(prompt: String) {
        updateStatus("Calling API...")
        ApiClient.sendPrompt(
            this,
            etApiKey.text.toString().trim(),
            etApiModel.text.toString().trim(),
            prompt,
            onResponse = { response ->
                runOnUiThread {
                    updateStatus("")
                    log("📥 API Response received.\n", Color.GREEN)
                    processAiResponse(response)
                }
            },
            onError = { error ->
                runOnUiThread {
                    updateStatus("")
                    log("❌ API Error: $error\n", Color.RED)
                    stopAgent()
                }
            }
        )
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
        // WebView is completely disabled. DeepSeek is commented out.
        Logger.i("Agent", "WebView setup skipped (API mode only)")
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

        val apiKey = etApiKey.text.toString().trim()
        val apiModel = etApiModel.text.toString().trim()
        
        if (apiKey.isEmpty()) {
            log("❌ API key is missing! Please enter it above.\n", Color.RED)
            stopAgent()
            return
        }

        val systemPrompt = """
            [INSTRUCTIONS]
            You are an expert Alpine Linux terminal bot. Your ONLY job is to output a single bash command wrapped in ```bash and ``` blocks.
            Do NOT explain anything. Do NOT use markdown outside the code block.
            You are running as root.
            [TASK]
            $prompt
        """.trimIndent()

        // فراخوانی مستقیم API
        sendToApi(systemPrompt)
        handler.postDelayed({ checkAiResponse() }, 2000)
    }

    private fun sendToAI(text: String) {
        // Deprecated. Handled by sendToApi now.
    }

    private var aiCheckAttempts = 0
    private var lastAiText = ""

    private fun checkAiResponse() {
        // Deprecated.
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
