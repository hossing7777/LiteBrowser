package com.litebrowser

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    // ✅ متغیرهای مربوط به نوار پیشرفت پروژه
    private lateinit var layoutProgress: LinearLayout
    private lateinit var pbTaskProgress: ProgressBar
    private lateinit var tvProgressPercent: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isPageLoaded = false
    private val logBuilder = StringBuilder()
    private var lastStatusLine = ""

    private val agentCodeFile by lazy { File(filesDir, "home/agent_code.txt") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("Agent", "AgentActivity onCreate started")
        setContentView(R.layout.activity_agent)
        
        etPrompt = findViewById(R.id.et_agent_prompt)
        btnStart = findViewById(R.id.btn_agent_start)
        tvLog = findViewById(R.id.tv_agent_log)
        tvStatus = findViewById(R.id.tv_agent_status)
        webView = findViewById(R.id.agent_webview)
        progressBar = findViewById(R.id.agent_progress)

        layoutProgress = findViewById(R.id.layout_progress)
        pbTaskProgress = findViewById(R.id.pb_task_progress)
        tvProgressPercent = findViewById(R.id.tv_progress_percent)

        val params = webView.layoutParams
        params.height = 1200
        webView.layoutParams = params

        handler.postDelayed({
            setupWebView()
            webView.loadUrl("https://chat.deepseek.com/")
        }, 800)

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
            if (prompt.isNotEmpty()) {
                startAgent(prompt)
            } else {
                Toast.makeText(this, "لطفا یک درخواست وارد کنید", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureTerminalSession() {
        if (TerminalManager.sessions.isEmpty() || TerminalManager.sessions.firstOrNull()?.isRunning == false) {
            Logger.i("Agent", "Creating new terminal session")
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
                "-b", "${homeDir.absolutePath}:/root",
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
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

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
                log("✅ DeepSeek page loaded. Ready to start.")
                Logger.i("Agent", "WebView loaded")
            }
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logBuilder.append(msg).append("\n")
            tvLog.text = logBuilder.toString()
            try {
                val scrollView = tvLog.parent as? ScrollView
                scrollView?.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            } catch (e: Exception) { }
        }
    }

    private fun updateStatus(status: String) {
        if (status == lastStatusLine) return
        lastStatusLine = status
        runOnUiThread {
            if (status.isEmpty()) {
                tvStatus.visibility = View.GONE
            } else {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "⏳ $status"
                
                if (status.contains("[Local Cache]")) {
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50")) // Green
                } else if (status.contains("[Internet]")) {
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#2196F3")) // Blue
                } else {
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#2196F3")) // Default Blue
                }
            }
        }
    }

    // ✅ متد جدید برای آپدیت درصد پیشرفت
    private fun updateProgress(percent: Int) {
        runOnUiThread {
            layoutProgress.visibility = View.VISIBLE
            pbTaskProgress.progress = percent
            tvProgressPercent.text = "$percent%"
            
            if (percent == 100) {
                tvProgressPercent.setTextColor(android.graphics.Color.parseColor("#4CAF50")) // Green for 100%
            } else {
                tvProgressPercent.setTextColor(android.graphics.Color.parseColor("#2196F3")) // Blue
            }
        }
    }

    private fun startAgent(prompt: String) {
        isRunning = true
        logBuilder.clear()
        updateStatus("")
        updateProgress(0) // ریست کردن نوار پیشرفت
        log("🤖 Starting Agent with task: $prompt\n")
        Logger.i("Agent", "startAgent called: $prompt")
        
        ensureTerminalSession()

        // ✅ پرامپت سیستم آپدیت شد تا درصد پیشرفت و chmod را رعایت کند
        val systemPrompt = """
            [INSTRUCTIONS]
            You are an Alpine Linux terminal bot. Your ONLY job is to output a single bash command wrapped in ```bash and ``` blocks.
            Do NOT explain anything. Do NOT repeat these instructions. Do NOT use markdown outside the code block.
            You are running as root.
            
            [PROGRESS REPORTING]
            After completing a major step of the task, you MUST include a progress indicator on a new line at the very end of your response (OUTSIDE the code block). Format it exactly as:
            [PROGRESS: X%]
            (Replace X with your estimated percentage of completion from 0 to 100. E.g., [PROGRESS: 50%])
            
            [SMART PACKAGE INSTALLATION]
            You have a local cache at /root/Local_AI_Models/Library_Cache/
            If you need to install an 'apk' package, YOU MUST use this exact logic (with colors):
            
            PACKAGE="package_name"
            CACHE_DIR="/root/Local_AI_Models/Library_Cache/apk"
            if ls ${'$'}CACHE_DIR/${'$'}PACKAGE-*.apk 1> /dev/null 2>&1; then
                echo -e "\033[32m📦 [Local Cache] Installing ${'$'}PACKAGE from local storage...\033[0m"
                apk add --no-cache --allow-untrusted ${'$'}CACHE_DIR/${'$'}PACKAGE-*.apk || { echo -e "\033[34m🌐 [Internet] Downloading ${'$'}PACKAGE...\033[0m"; apk add --no-cache ${'$'}PACKAGE; cp /var/cache/apk/*${'$'}PACKAGE*.apk ${'$'}CACHE_DIR/ 2>/dev/null; }
            else
                echo -e "\033[34m🌐 [Internet] Downloading ${'$'}PACKAGE...\033[0m"
                apk add --no-cache ${'$'}PACKAGE
                echo "[Saving] Saving ${'$'}PACKAGE to local cache..."
                cp /var/cache/apk/*${'$'}PACKAGE*.apk ${'$'}CACHE_DIR/ 2>/dev/null || true
            fi
            
            If you need to install a 'pip' package, YOU MUST use this exact logic:
            
            PACKAGE="package_name"
            CACHE_DIR="/root/Local_AI_Models/Library_Cache/pip"
            if ls ${'$'}CACHE_DIR | grep -q "^${'$'}PACKAGE-"; then
                echo -e "\033[32m📦 [Local Cache] Installing ${'$'}PACKAGE from local storage...\033[0m"
                pip install --no-index --find-links=${'$'}CACHE_DIR ${'$'}PACKAGE || { echo -e "\033[34m🌐 [Internet] Downloading ${'$'}PACKAGE...\033[0m"; pip install ${'$'}PACKAGE; pip download ${'$'}PACKAGE -d ${'$'}CACHE_DIR > /dev/null 2>&1; }
            else
                echo -e "\033[34m🌐 [Internet] Downloading ${'$'}PACKAGE...\033[0m"
                pip install ${'$'}PACKAGE
                echo "[Saving] Saving ${'$'}PACKAGE to local cache..."
                pip download ${'$'}PACKAGE -d ${'$'}CACHE_DIR > /dev/null 2>&1 || true
            fi
            
            [TASK]
            $prompt
        """.trimIndent()

        sendToAI(systemPrompt)
        handler.postDelayed({ checkAiResponse() }, 3000)
    }

    private fun sendToAI(text: String) {
        log("📤 Sending prompt to AI...")
        Logger.i("Agent", "Sending to AI...")
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
                        } else {
                            ta.innerText = text;
                        }
                        ta.dispatchEvent(new Event('input', { bubbles: true }));
                        ta.dispatchEvent(new Event('change', { bubbles: true }));
                        
                        setTimeout(function() {
                            ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true, cancelable: true }));
                            var buttons = document.querySelectorAll('button, div[role="button"]');
                            for (var i = buttons.length - 1; i >= 0; i--) {
                                var btn = buttons[i];
                                if (btn.innerHTML.includes('svg') && btn.offsetWidth > 0) {
                                    var rect = btn.getBoundingClientRect();
                                    var taRect = ta.getBoundingClientRect();
                                    if (rect.left > taRect.left + (taRect.width / 2) && rect.top > taRect.top) {
                                        btn.click();
                                        break;
                                    }
                                }
                            }
                        }, 500);
                    }
                } catch(e) {
                    AndroidBridge.onAgentError("JS Exception: " + e.message);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private var aiCheckAttempts = 0

    private fun checkAiResponse() {
        if (!isRunning) return
        aiCheckAttempts++
        
        if (aiCheckAttempts > 300) {
            log("❌ Error: Total timeout waiting for AI response.")
            Logger.e("Agent", "Total timeout waiting for AI response.")
            updateStatus("")
            isRunning = false
            return
        }

        val js = """
            (function() {
                try {
                    window.scrollTo(0, document.body.scrollHeight);
                    
                    var markdowns = document.querySelectorAll('.ds-markdown');
                    if (markdowns.length === 0) {
                        return JSON.stringify({ isGenerating: true, hasCode: false, taskComplete: false, alreadyProcessed: false, progress: -1 });
                    }
                    
                    var lastMd = markdowns[markdowns.length - 1];
                    
                    if (lastMd.getAttribute('data-processed') === 'true') {
                         return JSON.stringify({ isGenerating: false, hasCode: false, taskComplete: false, alreadyProcessed: true, progress: -1 });
                    }

                    var text = lastMd.innerText || lastMd.textContent || "";
                    
                    // استخراج درصد پیشرفت
                    var progressMatch = text.match(/\[PROGRESS:\s*(\d+)%\]/i);
                    var progressVal = progressMatch ? parseInt(progressMatch[1], 10) : -1;
                    
                    // حذف تگ پیشرفت از متن قبل از بررسی TASK_COMPLETE
                    var textClean = text.replace(/\[PROGRESS:\s*\d+%\]/gi, "").trim();
                    
                    if (textClean.includes("TASK_COMPLETE")) {
                        lastMd.setAttribute('data-processed', 'true');
                        return JSON.stringify({ isGenerating: false, hasCode: false, taskComplete: true, alreadyProcessed: false, progress: 100 });
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
                        if (match) {
                            codeText = match[1].trim();
                            hasCode = true;
                        }
                    }

                    var isGenerating = false;
                    if (textClean.endsWith("●") || textClean.endsWith("▍") || textClean.endsWith("_")) {
                        isGenerating = true;
                    }

                    if (hasCode && !isGenerating) {
                        lastMd.setAttribute('data-processed', 'true');
                        AndroidBridge.saveAgentCode(codeText);
                    }

                    return JSON.stringify({ isGenerating: isGenerating, hasCode: hasCode, taskComplete: false, alreadyProcessed: false, progress: progressVal });

                } catch(e) {
                    return JSON.stringify({ isGenerating: true, hasCode: false, taskComplete: false, alreadyProcessed: false, jsError: String(e), progress: -1 });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result == null || result == "null" || result == "\"\"") {
                handler.postDelayed({ checkAiResponse() }, 1000)
                return@evaluateJavascript
            }
            
            val cleanJson = result.removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            
            try {
                val jsonObj = org.json.JSONObject(cleanJson)
                val hasCode = jsonObj.getBoolean("hasCode")
                val isGenerating = jsonObj.getBoolean("isGenerating")
                val taskComplete = jsonObj.optBoolean("taskComplete", false)
                val alreadyProcessed = jsonObj.optBoolean("alreadyProcessed", false)
                val progress = jsonObj.optInt("progress", -1)
                
                if (taskComplete) {
                    log("\n🎉 TASK COMPLETE! The agent has finished the task successfully.")
                    updateStatus("")
                    updateProgress(100)
                    Logger.i("Agent", "Task Complete received from AI.")
                    isRunning = false
                    return@evaluateJavascript
                }

                // اگر درصد پیشرفت جدیدی دریافت شد، آپدیت کن
                if (progress >= 0) {
                    updateProgress(progress)
                }

                if (alreadyProcessed) {
                    handler.postDelayed({ checkAiResponse() }, 1000)
                    return@evaluateJavascript
                }

                if (!hasCode || isGenerating) {
                    handler.postDelayed({ checkAiResponse() }, 1000)
                    return@evaluateJavascript
                }
                
                if (agentCodeFile.exists()) {
                    val text = agentCodeFile.readText().trim()
                    agentCodeFile.delete() 
                    
                    if (text.isNotEmpty()) {
                        log("📥 AI response received. (${text.length} chars)")
                        Logger.i("Agent", "AI Code received from buffer file: ${text.take(50)}...")
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
        log("\n💻 Running Command:\n$command\n")
        
        val session = TerminalManager.sessions.firstOrNull()
        if (session != null && session.isRunning) {
            val scriptFile = File(filesDir, "home/run_agent.sh")
            val cleanCommand = command.replace("\r", "").trim() + "\n"
            scriptFile.writeText(cleanCommand)
            
            val startMarker = "___AGENT_START___"
            val endMarker = "___AGENT_END___"
            
            val execCmd = "echo \"$startMarker\"; sh /root/run_agent.sh < /dev/null; echo \"$endMarker\"\r"
            
            session.write(execCmd)
            log("⏳ Executing...")
            updateStatus("Starting execution...")
            handler.postDelayed({ readTerminalOutput(startMarker, endMarker, 0) }, 1500)
        } else {
            log("❌ Error: No terminal session.")
            isRunning = false
        }
    }

    private fun readTerminalOutput(startMarker: String, endMarker: String, attempts: Int) {
        if (!isRunning) return
        
        if (attempts > 300) { 
            log("❌ Error: Command execution timed out.")
            updateStatus("")
            isRunning = false
            return
        }

        val session = TerminalManager.sessions.firstOrNull()
        if (session == null) {
            handler.postDelayed({ readTerminalOutput(startMarker, endMarker, attempts + 1) }, 2000)
            return
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

                    if (finalOutput.length > 5000) {
                        finalOutput = finalOutput.take(5000) + "\n... (output truncated due to size)"
                    }

                    runOnUiThread {
                        updateStatus("") 
                        log("\n📄 Output:\n$finalOutput\n")
                        val nextPrompt = "Command Output:\n$finalOutput\n\nWhat is the next command? (Or reply TASK_COMPLETE if done)"
                        sendToAI(nextPrompt)
                        handler.postDelayed({ checkAiResponse() }, 3000)
                    }
                    return@thread
                }
            } else {
                val lines = screenText.trim().lines()
                if (lines.isNotEmpty()) {
                    val lastLine = lines.last().replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").trim()
                    if (lastLine.contains("[Local Cache]") || lastLine.contains("[Internet]") || 
                        lastLine.contains("[Saving]") || lastLine.contains("%") || 
                        (lastLine.isNotEmpty() && lastLine.length < 100)) {
                        runOnUiThread { updateStatus(lastLine) }
                    }
                }
            }
            
            handler.postDelayed({ readTerminalOutput(startMarker, endMarker, attempts + 1) }, 2000)
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
