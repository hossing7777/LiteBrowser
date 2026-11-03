package com.litebrowser

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
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
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isPageLoaded = false
    private val logBuilder = StringBuilder()

    private val outFile by lazy { File(filesDir, "home/agent_out.txt") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("Agent", "AgentActivity onCreate started")
        setContentView(R.layout.activity_agent)
        
        etPrompt = findViewById(R.id.et_agent_prompt)
        btnStart = findViewById(R.id.btn_agent_start)
        tvLog = findViewById(R.id.tv_agent_log)
        webView = findViewById(R.id.agent_webview)
        progressBar = findViewById(R.id.agent_progress)

        handler.postDelayed({
            Logger.i("Agent", "Setting up WebView")
            setupWebView()
            Logger.i("Agent", "Loading DeepSeek URL")
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
        Logger.i("Agent", "AgentActivity onCreate finished")
    }

    private fun ensureTerminalSession() {
        if (TerminalManager.sessions.isEmpty() || TerminalManager.sessions.firstOrNull()?.isRunning == false) {
            Logger.i("Agent", "No active terminal session. Creating a new one...")
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
                "-w", "/root", "/bin/sh", "-l"
            )

            val session = TerminalSession(prootBin.absolutePath, homeDir.absolutePath, args, envVars, 2000, TerminalManager)
            TerminalManager.addSession(session)
            Logger.i("Agent", "New terminal session created with Download mounted.")
        } else {
            Logger.i("Agent", "Terminal session already active.")
        }
    }

    private fun setupWebView() {
        try {
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
                    Logger.i("Agent", "WebView loading finished: $url")
                    log("✅ DeepSeek page loaded. Ready to start.")
                }
            }
        } catch (e: Exception) {
            Logger.e("Agent", "Failed to setup WebView", e)
        }

        handler.postDelayed({
            if (!isPageLoaded) {
                Logger.w("Agent", "onPageFinished timeout. Forcing page loaded state.")
                isPageLoaded = true
                runOnUiThread { progressBar.visibility = View.GONE }
                log("⚠️ Page load timeout. Proceeding...")
            }
        }, 5000)
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

    private fun startAgent(prompt: String) {
        isRunning = true
        logBuilder.clear()
        log("🤖 Starting Agent with task: $prompt\n")
        Logger.i("Agent", "startAgent called with prompt: $prompt")
        
        ensureTerminalSession()

        val systemPrompt = """
            You are an AI autonomous agent operating inside an Alpine Linux terminal.
            Your goal is to complete the user's task by writing and executing bash commands.
            You MUST respond ONLY with a bash code block like this:
            ```bash
            your_command_here
            ```
            Do not include any explanation or text outside the code block.
            If the task is completely finished, respond with exactly: TASK_COMPLETE
            
            IMPORTANT RULES:
            1. To install packages, use: `apk add --cache-dir /mnt/cache/apk <package>`
            2. You are running as root.
            3. If a command fails, analyze the error and try again.
            4. The Android "Download" folder is mounted at `/root/Download`. Save files there if requested.
            
            User Task: $prompt
        """.trimIndent()

        sendToAI(systemPrompt)
        handler.postDelayed({ checkAiResponse() }, 3000)
    }

    private fun sendToAI(text: String) {
        Logger.i("Agent", "sendToAI called. Sending prompt to DeepSeek...")
        log("📤 Sending prompt to AI...")
        
        val safePrompt = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
        
        val js = """
            (function() {
                try {
                    var ta = document.querySelector('textarea');
                    if (!ta) {
                        ta = document.querySelector('div[contenteditable="true"]');
                        if (ta) {
                            ta.innerText = "$safePrompt";
                            ta.dispatchEvent(new Event('input', { bubbles: true }));
                        } else {
                            AndroidBridge.onAgentError("Input field not found.");
                            return;
                        }
                    } else {
                        var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                        nativeSetter.call(ta, "$safePrompt");
                        ta.dispatchEvent(new Event('input', { bubbles: true }));
                        ta.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    
                    setTimeout(function() {
                        ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
                        var buttons = document.querySelectorAll('button, div[role="button"]');
                        var clicked = false;
                        for (var i = buttons.length - 1; i >= 0; i--) {
                            var btn = buttons[i];
                            if (btn.querySelector('svg') && btn.offsetWidth > 0) {
                                var rect = btn.getBoundingClientRect();
                                var taRect = ta.getBoundingClientRect();
                                if (rect.left > taRect.left + (taRect.width / 2)) {
                                    btn.click();
                                    clicked = true;
                                    break;
                                }
                            }
                        }
                        AndroidBridge.logDebug("Send button clicked: " + clicked);
                    }, 500);
                } catch(e) {
                    AndroidBridge.onAgentError("JS Exception: " + e.message);
                }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { result ->
            Logger.i("Agent", "JS evaluation result: $result")
        }
    }

    private var lastAiText = ""
    private var stableCounter = 0
    private var aiCheckAttempts = 0

    private fun checkAiResponse() {
        if (!isRunning) return
        
        aiCheckAttempts++
        if (aiCheckAttempts % 5 == 0) {
            Logger.i("Agent", "Polling AI... attempt $aiCheckAttempts")
        }
        if (aiCheckAttempts > 180) { // ۳ دقیقه تایم‌اوت
            Logger.e("Agent", "Timeout waiting for AI response.")
            log("❌ Error: Timeout waiting for AI response.")
            isRunning = false
            return
        }

        // ✅ استخراج به روش Brute-force: خواندن کل متن و پیدا کردن آخرین بلوک کد در کاتلین
        val js = """
            (function() {
                var clones = document.body.cloneNode(true);
                // حذف المان‌های غیرضروری
                var toRemove = clones.querySelectorAll('textarea, input, header, nav, footer, .ds-icon-button, button');
                for(var i=0; i<toRemove.length; i++) { toRemove[i].remove(); }
                var text = clones.innerText;
                
                // چک کردن اینکه آیا دکمه Stop (در حال تایپ) وجود دارد یا خیر
                var isGenerating = document.querySelector('button[aria-label*="Stop"], [class*="stop-button"]') !== null;
                
                return JSON.stringify({ text: text, isGenerating: isGenerating });
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result != null && result != "null" && result != "\"\"") {
                // پاکسازی خروجی JSON
                val cleanJson = result.replace("\\\"", "\"")
                    .replace("\\\\n", "\n")
                    .replace("\\\\", "\\")
                    .removeSurrounding("\"")
                
                try {
                    val jsonObj = org.json.JSONObject(cleanJson)
                    val text = jsonObj.getString("text")
                    val isGenerating = jsonObj.getBoolean("isGenerating")
                    
                    // اگر هوش مصنوعی در حال تولید است یا متن خالی است، صبر می‌کنیم
                    if (isGenerating || text.trim().isEmpty()) {
                        lastAiText = ""
                        stableCounter = 0
                        handler.postDelayed({ checkAiResponse() }, 1000)
                        return@evaluateJavascript
                    }
                    
                    if (text == lastAiText) {
                        stableCounter++
                        if (stableCounter >= 3) { // ۳ ثانیه ثابت ماند
                            // پیدا کردن آخرین بلوک کد bash
                            val regex = Regex("```(?:bash|sh)?\\n([\\s\\S]*?)```")
                            val matches = regex.findAll(text)
                            val lastMatch = matches.lastOrNull()
                            
                            if (lastMatch != null) {
                                val command = lastMatch.groupValues[1].trim()
                                Logger.i("Agent", "Stable AI response found. Extracted command length: ${command.length}")
                                log("📥 AI response received.")
                                stableCounter = 0
                                lastAiText = ""
                                aiCheckAttempts = 0
                                processAiResponse(command)
                                return@evaluateJavascript
                            }
                        }
                    } else {
                        stableCounter = 0
                        lastAiText = text
                    }
                } catch (e: Exception) {
                    Logger.e("Agent", "JSON parsing failed: ${e.message}")
                }
            }
            handler.postDelayed({ checkAiResponse() }, 1000)
        }
    }

    private var cmdCheckAttempts = 0
    private fun processAiResponse(command: String) {
        log("\n💻 Running Command:\n$command\n")
        Logger.i("Agent", "Extracted command: $command")
        
        val session = TerminalManager.sessions.firstOrNull()
        if (session != null && session.isRunning) {
            cmdCheckAttempts = 0
            val execCmd = "$command > /root/agent_out.txt 2>&1; echo '##DONE##' >> /root/agent_out.txt\r"
            session.write(execCmd)
            Logger.i("Agent", "Command sent to terminal.")
            log("⏳ Executing command in terminal...")
            handler.postDelayed({ readTerminalOutput() }, 1500)
        } else {
            val err = "No active terminal session. Please open terminal first."
            Logger.e("Agent", err)
            log("❌ Error: $err")
            isRunning = false
        }
    }

    private fun readTerminalOutput() {
        if (!isRunning) return
        
        cmdCheckAttempts++
        if (cmdCheckAttempts > 60) {
            Logger.e("Agent", "Timeout waiting for command execution.")
            log("❌ Error: Command execution timed out.")
            isRunning = false
            return
        }

        thread {
            if (outFile.exists()) {
                val content = outFile.readText()
                if (content.contains("##DONE##")) {
                    val output = content.replace("##DONE##", "").trim()
                    Logger.i("Agent", "Command output: $output")
                    log("\n📄 Output:\n$output\n")
                    outFile.delete() 
                    
                    val nextPrompt = "Command Output:\n$output\n\nWhat is the next command? (Or reply TASK_COMPLETE if done)"
                    runOnUiThread { sendToAI(nextPrompt) }
                    handler.postDelayed({ checkAiResponse() }, 3000)
                } else {
                    handler.postDelayed({ readTerminalOutput() }, 1000)
                }
            } else {
                handler.postDelayed({ readTerminalOutput() }, 1000)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        Logger.i("Agent", "AgentActivity onDestroy")
        super.onDestroy()
    }
}
