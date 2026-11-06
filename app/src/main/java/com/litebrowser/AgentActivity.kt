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
import android.view.View
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
    private lateinit var btnToggleView: Button
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDataUsage: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var layoutProgress: LinearLayout
    private lateinit var pbTaskProgress: ProgressBar
    private lateinit var tvProgressPercent: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isLogsHidden = false
    private val logBuilder = SpannableStringBuilder()
    private var lastStatusLine = ""

    private var startRx = 0L
    private var startTx = 0L
    private var totalRx = 0L
    private var totalTx = 0L

    private val agentMemoryFile by lazy { File(filesDir, "home/agent_memory.txt") }

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
        btnToggleView = findViewById(R.id.btn_toggle_view)
        tvLog = findViewById(R.id.tv_agent_log)
        tvStatus = findViewById(R.id.tv_agent_status)
        tvDataUsage = findViewById(R.id.tv_data_usage)
        scrollLogs = findViewById(R.id.scroll_logs)
        layoutProgress = findViewById(R.id.layout_progress)
        pbTaskProgress = findViewById(R.id.pb_task_progress)
        tvProgressPercent = findViewById(R.id.tv_progress_percent)

        requestNotificationPermission()

        totalRx = TrafficStats.getUidRxBytes(Process.myUid())
        totalTx = TrafficStats.getUidTxBytes(Process.myUid())
        if (totalRx == TrafficStats.UNSUPPORTED.toLong()) totalRx = 0L
        if (totalTx == TrafficStats.UNSUPPORTED.toLong()) totalTx = 0L

        btnToggleView.setOnClickListener {
            isLogsHidden = !isLogsHidden
            if (isLogsHidden) {
                scrollLogs.visibility = View.GONE
                btnToggleView.text = "📜 نمایش لاگ"
            } else {
                scrollLogs.visibility = View.VISIBLE
                btnToggleView.text = "🖥️ بزرگ‌نمایی"
            }
            scrollLogs.requestLayout()
        }

        btnHistory.setOnClickListener {
            startActivity(Intent(this, ChatHistoryActivity::class.java))
        }

        btnStart.setOnClickListener {
            if (isRunning) {
                Toast.makeText(this, "ایجنت در حال اجراست...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val store = Store(this)
            if (store.hormouzApiKey.isBlank()) {
                Toast.makeText(this, "لطفاً ابتدا کلید API هرمز را در تنظیمات وارد کنید", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, HormouzSettingsActivity::class.java))
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
                btnStart.isEnabled = false
                btnStop.visibility = View.VISIBLE
                etPrompt.isEnabled = false
            } else {
                btnStart.isEnabled = true
                btnStop.visibility = View.GONE
                etPrompt.isEnabled = true
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

            val session = TerminalSession(prootBin.absolutePath, homeDir.absolutePath, args, envVars, 1000, TerminalManager)
            session.updateSize(1000, 24)
            TerminalManager.addSession(session)
        }
    }

    private fun log(msg: String, color: Int = Color.WHITE) {
        runOnUiThread {
            val start = logBuilder.length
            logBuilder.append(msg).append("\n")
            logBuilder.setSpan(ForegroundColorSpan(color), start, logBuilder.length, 0)
            if (logBuilder.length > 50000) logBuilder.delete(0, 20000)
            tvLog.text = logBuilder
            try {
                scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
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
            You are an expert Alpine Linux terminal bot. Your ONLY job is to output a single shell command wrapped in ```sh and ``` blocks.
            Do NOT explain anything. Do NOT use markdown outside the code block.
            You are running as root.
            CRITICAL RULES: Use `sh` instead of `bash`. Do NOT use `apk fetch`. Use `apk add --no-cache`. For Python use `pip3 install --break-system-packages`. Output TASK_COMPLETE when done.

            [PROGRESS REPORTING]
            After each major step, on a new line OUTSIDE the code block, write:
            [PROGRESS: X%]

            [PERSISTENT MEMORY]
            If you learn a critical fact, you may include it OUTSIDE the code block like:
            [NOTE: your important fact here]

            [ADVANCED CAPABILITIES]
            1. WEB SCRAPING: Use Python (apk add python3 py3-pip py3-requests py3-beautifulsoup4 py3-pandas py3-lxml py3-openpyxl) for data extraction to CSV/Excel/JSON.
            2. MUSIC: Use yt-dlp (if network allows) or direct download via wget.
            3. DOCUMENTS: Use pandoc and wkhtmltopdf for PDF/Word. For Persian PDFs, use HTML with dir="rtl" and Noto fonts.

            [SMART PACKAGE INSTALLATION & OFFLINE CACHE]
            PACKAGE="package_name"
            CACHE_DIR="/root/Local_AI_Models/Library_Cache/apk"
            mkdir -p "${'$'}CACHE_DIR"
            if ls ${'$'}CACHE_DIR/${'$'}PACKAGE-*.apk 1> /dev/null 2>&1; then
                apk add --no-cache --allow-untrusted ${'$'}CACHE_DIR/${'$'}PACKAGE-*.apk || apk add --no-cache ${'$'}PACKAGE
            else
                apk fetch --recursive -o "${'$'}CACHE_DIR" ${'$'}PACKAGE
                apk add --no-cache --allow-untrusted ${'$'}CACHE_DIR/*.apk || apk add --no-cache ${'$'}PACKAGE
            fi

            [SELF-UPGRADE PROTOCOL - READ CAREFULLY]
            You are an autonomous Android developer. Your main goal is to upgrade yourself based on:
            1. PRIORITY 1: Keywords found at /root/Download/Local_AI_Models/tools_keywords.txt
            2. PRIORITY 2: User requests and your own internal needs.
            3. PRIORITY 3: Your own free will to improve yourself.

            [HOW TO BUILD A TOOL]
            You have full access. If you decide to build a new tool:
            1. Create a folder at /root/Download/Local_AI_Models/Tools_app/ToolName_Date/
            2. Inside it, create the tool files (e.g., script.py, script.sh).
            3. Create an 'info.txt' file with exactly 3 lines:
               Line 1: Persian description (فارسی)
               Line 2: English description
               Line 3: STATUS (Write "TESTING" first. After you run tests and fix all bugs/warnings, change it to "READY").
            4. You MUST fully test the tool. Once it works perfectly, change line 3 of info.txt to "READY" so it gets a Green Light in the Tools tab.

            [CRITICAL RULES FOR FILE EDITING]
            - NEVER use cat << EOF directly for Kotlin files. Use Python scripts to modify files safely.
            - Use Base64 or Python to write complex text to avoid shell truncation.

            [TASK]
            $prompt
        """.trimIndent()

        sendToAI(systemPrompt)
    }

    private fun sendToAI(text: String) {
        log("📤 Sending prompt to Hormouz API...", Color.BLUE)
        updateDataUsage()

        val store = Store(this)
        val apiKey = store.hormouzApiKey
        val model = store.hormouzModel

        if (apiKey.isBlank()) {
            log("❌ Error: API Key not set. Please go to Settings.", Color.RED)
            stopAgent()
            return
        }

        thread {
            val messages = listOf(
                HormouzClient.Message("system", "You are an expert Alpine Linux terminal bot. Your ONLY job is to output a single bash command wrapped in ```bash and ``` blocks. Do NOT explain anything. Do NOT use markdown outside the code block. You are running as root.\n[ADVANCED CAPABILITIES]\n1. WEB SCRAPING: Use Python (apk add python3 py3-pip py3-requests py3-beautifulsoup4 py3-pandas py3-lxml py3-openpyxl) for data extraction to CSV/Excel/JSON.\n2. MUSIC: Use yt-dlp (if network allows) or direct download via wget.\n3. DOCUMENTS: Use pandoc and wkhtmltopdf for PDF/Word. For Persian PDFs, use HTML with dir=\"rtl\" and Noto fonts.\n[PROGRESS REPORTING]\nAfter each major step, on a new line OUTSIDE the code block, write: [PROGRESS: X%]\n[PERSISTENT MEMORY]\nIf you learn a critical fact, you may include it OUTSIDE the code block like: [NOTE: your important fact here]"),
                HormouzClient.Message("user", text)
            )

            val response = HormouzClient.sendRequest(apiKey, model, messages)

            runOnUiThread {
                if (response.success && response.content != null) {
                    log("📥 AI response received.", Color.GREEN)
                    extractAiNotes(response.content)
                    processAiResponse(response.content)
                } else {
                    log("❌ API Error: ${response.error}", Color.RED)
                    stopAgent()
                }
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

                        log("⏳ Waiting 10s to avoid rate limit...", Color.GRAY)
                        handler.postDelayed({
                            if (isRunning) sendToAI(nextPrompt)
                        }, 10000)
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
