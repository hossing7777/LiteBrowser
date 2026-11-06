package com.litebrowser

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
    private val logBuilder = SpannableStringBuilder()
    private var lastStatusLine = ""
    private var startRx = 0L
    private var startTx = 0L
    private val agentMemoryFile by lazy { File(filesDir, "home/agent_memory.txt") }
    private val recentSteps = mutableListOf<Pair<String, String>>()
    private val aiNotes = mutableListOf<String>()
    private var originalTask: String = ""
    private var lastExecutedCommand: String = ""
    private var currentChatFolder: File? = null
    private val currentChatMessages = mutableListOf<ChatMessage>()
    private var aiRetryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        tvLog.setTextIsSelectable(true)
        tvLog.movementMethod = ScrollingMovementMethod()
        tvLog.isFocusable = true
        tvLog.isFocusableInTouchMode = true
        tvLog.isLongClickable = true
        tvLog.setOnLongClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Agent Log", tvLog.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "لاگ با موفقیت کپی شد", Toast.LENGTH_SHORT).show()
            true
        }

        startRx = TrafficStats.getUidRxBytes(Process.myUid())
        startTx = TrafficStats.getUidTxBytes(Process.myUid())
        if (startRx == TrafficStats.UNSUPPORTED.toLong()) startRx = 0L
        if (startTx == TrafficStats.UNSUPPORTED.toLong()) startTx = 0L

        requestNotificationPermission()
        btnToggleView.setOnClickListener { toggleLogView() }
        btnHistory.setOnClickListener { startActivity(Intent(this, ChatHistoryActivity::class.java)) }

        btnStart.setOnClickListener {
            if (isRunning) { Toast.makeText(this, "ایجنت در حال اجراست...", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val store = Store(this)
            if (store.hormouzApiKey.isBlank()) {
                Toast.makeText(this, "لطفاً ابتدا کلید API هرمز را در تنظیمات وارد کنید", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, HormouzSettingsActivity::class.java)); return@setOnClickListener
            }
            val prompt = etPrompt.text.toString().trim()
            if (prompt.isNotEmpty()) startAgent(prompt)
            else Toast.makeText(this, "لطفا یک درخواست وارد کنید", Toast.LENGTH_SHORT).show()
        }
        btnStop.setOnClickListener { stopAgent() }

        handler.post(dataUsageUpdater)
    }

    private val dataUsageUpdater = object : Runnable {
        override fun run() {
            val currentRx = TrafficStats.getUidRxBytes(Process.myUid())
            val currentTx = TrafficStats.getUidTxBytes(Process.myUid())
            if (currentRx != TrafficStats.UNSUPPORTED.toLong() && currentTx != TrafficStats.UNSUPPORTED.toLong()) {
                val totalDeltaRx = currentRx - startRx
                val totalDeltaTx = currentTx - startTx
                val totalMB = (totalDeltaRx + totalDeltaTx) / (1024.0 * 1024.0)
                tvDataUsage.text = String.format("Data used: %.2f MB", totalMB)
            }
            handler.postDelayed(this, 2000)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("agent_errors", "Agent Errors", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Notifications for agent errors and issues"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String) {
        try {
            createNotificationChannel()
            val intent = Intent(this, AgentActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val builder = NotificationCompat.Builder(this, "agent_errors")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), builder.build())
                }
            } else {
                NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), builder.build())
            }
        } catch (e: Exception) {
            Logger.e("Agent", "Failed to show notification", e)
        }
    }

    private fun toggleLogView() {
        if (scrollLogs.visibility == View.VISIBLE) {
            scrollLogs.visibility = View.GONE
            btnToggleView.text = "📜 نمایش لاگ"
        } else {
            scrollLogs.visibility = View.VISIBLE
            btnToggleView.text = "🖥️ بزرگ‌نمایی"
        }
    }

    private fun stopAgent() {
        if (isRunning) {
            isRunning = false
            handler.removeCallbacksAndMessages(null)
            val session = TerminalManager.sessions.firstOrNull()
            if (session != null && session.isRunning) session.write("\u0003")
            log("\n🛑 Agent stopped by user.", Color.RED)
            updateStatus(""); updateProgress(0); toggleButtons(running = false)
            stopService(Intent(this, AgentForegroundService::class.java))
            handler.post(dataUsageUpdater)
        }
    }

    private fun toggleButtons(running: Boolean) {
        runOnUiThread {
            btnStart.isEnabled = !running
            btnStop.visibility = if (running) View.VISIBLE else View.GONE
            etPrompt.isEnabled = !running
        }
    }

    private fun ensureTerminalSession() {
        (application as LiteApp).writeDnsConfig()
        if (TerminalManager.sessions.isEmpty() || TerminalManager.sessions.firstOrNull()?.isRunning == false) {
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
                prootBin.absolutePath, "--link2symlink", "-0", "-r", alpineRoot.absolutePath,
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
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
            Logger.i("AgentLog", msg)
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
                tvStatus.setTextColor(Color.parseColor("#2196F3"))
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
            val noteRegex = Regex("\\[NOTE:\\s*([^\\]]+)\\]")
            noteRegex.findAll(text).forEach { match ->
                val note = match.groupValues[1].trim()
                if (note.isNotEmpty() && !aiNotes.contains(note)) {
                    aiNotes.add(note)
                    if (aiNotes.size > 20) aiNotes.removeAt(0)
                    Logger.i("Agent", "AI note saved: $note")
                }
            }
            val progressRegex = Regex("\\[PROGRESS:\\s*(\\d+)%\\]")
            progressRegex.findAll(text).forEach { match ->
                val percent = match.groupValues[1].toIntOrNull()
                if (percent != null) {
                    updateProgress(percent)
                }
            }
        } catch (e: Exception) { Logger.e("Agent", "extractAiNotes error", e) }
    }

    private fun startAgent(prompt: String) {
        isRunning = true
        toggleButtons(running = true)
        logBuilder.clear()
        updateStatus("")
        updateProgress(0)
        aiRetryCount = 0

        startRx = TrafficStats.getUidRxBytes(Process.myUid())
        startTx = TrafficStats.getUidTxBytes(Process.myUid())
        if (startRx == TrafficStats.UNSUPPORTED.toLong()) startRx = 0L
        if (startTx == TrafficStats.UNSUPPORTED.toLong()) startTx = 0L

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
            You are running as root in Alpine Linux.
            
            [CRITICAL RULES]
            - You MUST format your entire response inside the ```sh ... ``` block. 
            - Use `sh` instead of `bash`.
            - NEVER try to download or install .apk files manually. NEVER use `wget` or `curl` for packages.
            - To install ANY package (system or python), ONLY use: `install_pkg <name1> <name2>`
            - For PDF generation, use `weasyprint` (install via `install_pkg weasyprint`).
            - When the task is completely finished, output ONLY the word `TASK_COMPLETE` inside the sh block.
            - If you are just reporting a milestone, output `echo 'Step X Completed'` inside the block.
            - Do NOT install packages that are not needed (like transformers, torch).

            [AUTO-RECOVERY SYSTEM]
            If a command fails because a tool is missing (e.g., 'command not found'), DO NOT try to download it manually. Run `install_pkg <toolname>` and then try the original command again.
            If a Python script fails with 'ModuleNotFoundError', run `install_pkg <module_name>` and try again.

            [TASK]
            $prompt
        """.trimIndent()

        sendToAI(systemPrompt)
    }

    private fun sendToAI(text: String) {
        log("📤 Sending prompt to Hormouz API...", Color.BLUE)
        val store = Store(this)
        val apiKey = store.hormouzApiKey
        val model = store.hormouzModel

        if (apiKey.isBlank()) {
            log("❌ Error: API Key not set. Please go to Settings.", Color.RED)
            stopAgent(); return
        }

        thread {
            val messages = listOf(HormouzClient.Message("user", text))
            val response = HormouzClient.sendRequest(apiKey, model, messages)
            runOnUiThread {
                if (response.success && response.content != null && response.content.trim().isNotEmpty()) {
                    log("📥 AI response received.", Color.GREEN)
                    aiRetryCount = 0
                    extractAiNotes(response.content)
                    processAiResponse(response.content)
                } else {
                    if (aiRetryCount < 2) {
                        aiRetryCount++
                        log("⚠️ AI returned null (server busy). Retrying ($aiRetryCount/2)...", Color.YELLOW)
                        handler.postDelayed({ 
                            if (isRunning) {
                                sendToAI("[SYSTEM] Your last response was empty. Please try again. You MUST output a valid bash command inside a ```sh block. Do not repeat the previous command if it was already executed. Original task: $originalTask")
                            }
                        }, 5000)
                    } else {
                        aiRetryCount = 0
                        log("❌ AI failed to respond after 2 attempts. Stopping.", Color.RED)
                        showNotification("Agent API Error", "AI returned null twice. Please check your API key or network.")
                        stopAgent()
                    }
                }
            }
        }
    }

    private fun processAiResponse(fullResponse: String) {
        // 1. استخراج محتوای بلوک کد (پشتیبانی از ```sh, ```, ```bash)
        var command: String? = null
        
        // تلاش با سه بک‌تیک
        val tripleBacktickRegex = Regex("```(?:bash|sh|shell)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val tripleMatch = tripleBacktickRegex.find(fullResponse)
        if (tripleMatch != null) {
            command = tripleMatch.groupValues[1].trim()
        } else {
            // تلاش با یک بک‌تیک (مثل `pip install ...`)
            val singleBacktickRegex = Regex("`([^`]+)`")
            val singleMatch = singleBacktickRegex.find(fullResponse)
            if (singleMatch != null) {
                command = singleMatch.groupValues[1].trim()
            }
        }

        // 2. اگر بلاک کد پیدا نشد، خط به خط بگرد
        if (command.isNullOrEmpty()) {
            val lines = fullResponse.lines()
            val commandLines = mutableListOf<String>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[PROGRESS:") || trimmed.startsWith("[NOTE:") || trimmed == "TASK_COMPLETE" || trimmed.startsWith("ERROR:") || trimmed.startsWith("Step ")) continue
                if (trimmed.startsWith("apk ") || trimmed.startsWith("install_pkg ") || trimmed.startsWith("pip ") || trimmed.startsWith("pip3 ") || 
                    trimmed.startsWith("mkdir ") || trimmed.startsWith("cd ") || trimmed.startsWith("echo ") || 
                    trimmed.startsWith("python3 ") || trimmed.startsWith("cat ") || trimmed.startsWith("printf ") || 
                    trimmed.startsWith("sed ") || trimmed.startsWith("wget ") || trimmed.startsWith("curl ") || 
                    trimmed.startsWith("which ") || trimmed.startsWith("chmod ") || trimmed.startsWith("sh ") ||
                    trimmed.startsWith("cd/") || trimmed.startsWith("/")) {
                    commandLines.add(line)
                }
            }
            command = commandLines.joinToString("\n").trim()
        }

        if (fullResponse.contains("TASK_COMPLETE") && (command.isNullOrEmpty() || !command.contains("TASK_COMPLETE"))) {
            log("\n🎉 TASK COMPLETE detected!", Color.GREEN)
            showNotification("Agent Task Complete", "The AI agent has finished the task successfully.")
            updateProgress(100); stopAgent(); return
        }

        if (command.isNullOrEmpty()) {
            log("⚠️ Could not extract valid command. AI Response: $fullResponse", Color.YELLOW)
            showNotification("Agent Parse Error", "The AI response was not a valid command. Retrying...")
            handler.postDelayed({
                if (isRunning) {
                    sendToAI("[ERROR] Your last response did not contain a bash command. Please try again. You MUST output ONLY the bash command inside the block. Do not include any explanations. Original task: $originalTask")
                }
            }, 5000)
            return
        }

        // ضدعفونی کردن: جلوگیری از نصب پکیج‌های غیرضروری
        if (command.contains("transformers") || command.contains("torch")) {
            log("⚠️ Agent tried to install unnecessary packages. Redirecting...", Color.YELLOW)
            sendToAI("[ERROR] Do NOT install transformers or torch. They are not needed for this task. Please proceed with the next step.")
            return
        }

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
            log("❌ Error: No terminal session.", Color.RED); stopAgent()
        }
    }

    private fun readTerminalOutput(startMarker: String, endMarker: String, attempts: Int) {
        if (!isRunning) return
        if (attempts > 2400) { 
            log("❌ Error: Timed out after 20 minutes.", Color.RED)
            showNotification("Agent Timeout", "The command took too long and was stopped.")
            stopAgent(); return 
        }
        val session = TerminalManager.sessions.firstOrNull()
        if (session == null) { 
            handler.postDelayed({ readTerminalOutput(startMarker, endMarker, attempts + 1) }, 500)
            return 
        }

        thread {
            val screenText = session.emulator?.screen?.getTranscriptText() ?: ""
            if (screenText.contains(endMarker)) {
                val startIndex = screenText.lastIndexOf(startMarker)
                val endIndex = screenText.lastIndexOf(endMarker)
                if (startIndex != -1 && endIndex > startIndex) {
                    val rawOutput = screenText.substring(startIndex + startMarker.length, endIndex)
                    val cleanOutput = rawOutput.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
                        .lines().filterNot { it.contains("sh /root/run_agent.sh") || it.contains("echo \"___AGENT") }
                        .joinToString("\n").trim()
                    val finalOutput = if (cleanOutput.isEmpty()) "Command executed (no output)." else cleanOutput.take(5000)

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

                        if (fullOutputContainsTaskComplete(finalOutput)) {
                            log("\n🎉 TASK COMPLETE detected in output!", Color.GREEN)
                            showNotification("Agent Task Complete", "The AI agent has finished the task successfully.")
                            updateProgress(100); stopAgent(); return@runOnUiThread
                        }

                        var autoFixHint = ""
                        if (finalOutput.contains("not found") && !finalOutput.contains("install_pkg")) {
                            val missingTool = Regex("([a-zA-Z0-9_\\-]+): not found").find(finalOutput)?.groupValues?.get(1)
                            if (missingTool != null) {
                                autoFixHint = "\n[AUTO-FIX] The tool '$missingTool' is missing. You MUST run 'install_pkg $missingTool' first, then retry the command."
                                log("🔧 Auto-fix detected missing tool: $missingTool", Color.MAGENTA)
                            }
                        } else if (finalOutput.contains("ModuleNotFoundError: No module named")) {
                            val missingModule = Regex("No module named '([^']+)'").find(finalOutput)?.groupValues?.get(1)
                            if (missingModule != null) {
                                autoFixHint = "\n[AUTO-FIX] Python module '$missingModule' is missing. You MUST run 'install_pkg $missingModule' first, then retry the script."
                                log("🔧 Auto-fix detected missing module: $missingModule", Color.MAGENTA)
                            }
                        }

                        val nextPrompt = buildMemoryContext() +
                            "\n[COMMAND OUTPUT TO ANALYZE]\n$finalOutput$autoFixHint\n\nWhat is the next command? (Or reply TASK_COMPLETE if done)"
                        
                        log("⏳ Waiting 10s to avoid rate limit...", Color.GRAY)
                        handler.postDelayed({ if (isRunning) sendToAI(nextPrompt) }, 10000)
                    }
                    return@thread
                }
            }
            
            val lines = screenText.trim().lines()
            if (lines.isNotEmpty()) {
                val lastLine = lines.last().replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").trim()
                if (lastLine.isNotEmpty() && lastLine != lastStatusLine) {
                    runOnUiThread {
                        updateStatus(lastLine)
                        val logStart = logBuilder.length
                        logBuilder.append("   ").append(lastLine).append("\n")
                        logBuilder.setSpan(ForegroundColorSpan(Color.GRAY), logStart, logBuilder.length, 0)
                        if (logBuilder.length > 50000) logBuilder.delete(0, 20000)
                        tvLog.text = logBuilder
                        scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
            
            handler.postDelayed({ readTerminalOutput(startMarker, endMarker, attempts + 1) }, 500)
        }
    }

    private fun fullOutputContainsTaskComplete(output: String): Boolean {
        return output.contains("TASK_COMPLETE") && output.length < 100
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
