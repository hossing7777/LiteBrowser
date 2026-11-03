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
            
            CRITICAL RULES:
            1. You MUST respond ONLY with a single bash code block like this:
            ```bash
            your_command_here
            ```
            2. Do NOT include any explanation, thoughts, or text outside the code block.
            3. If a command fails, analyze the error and try again.
            4. You are running as root.
            5. The Android "Download" folder is mounted at `/root/Download`.
            6. If the user asks to create a file (e.g., test.py), use:
               cat << 'EOF' > /root/Download/test.py
               print("Hello")
               EOF
            7. If the user asks to run a script, use:
               python3 /root/Download/test.py
            8. If you need to install packages, use: `apk add python3`
            9. If the task is completely finished, respond with exactly: TASK_COMPLETE
            
            User Task: $prompt
        """.trimIndent()

        sendToAI(systemPrompt)
        handler.postDelayed({ checkAiResponse() }, 3000)
    }

    private fun sendToAI(text: String) {
        Logger.i("Agent", "sendToAI called. Sending prompt to DeepSeek...")
        log("📤 Sending prompt to AI...")
        
        val base64Prompt = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        
        val js = """
            (function() {
                try {
                    var base64Text = "$base64Prompt";
                    var text = decodeURIComponent(escape(atob(base64Text)));
                    
                    var ta = document.querySelector('textarea');
                    if (!ta) {
                        ta = document.querySelector('div[contenteditable="true"]');
                        if (ta) {
                            ta.innerText = text;
                            ta.dispatchEvent(new Event('input', { bubbles: true }));
                        } else {
                            AndroidBridge.onAgentError("Input field not found.");
                            return;
                        }
                    } else {
                        var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                        nativeSetter.call(ta, text);
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
        
        webView.evaluateJavascript(js, null)
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
        if (aiCheckAttempts > 300) { // ۵ دقیقه تایم‌اوت برای حالت DeepThink
            Logger.e("Agent", "Timeout waiting for AI response.")
            log("❌ Error: Timeout waiting for AI response.")
            isRunning = false
            return
        }

        // ✅ فقط به دنبال تگ‌های <pre> (بلوک کد) می‌گردیم
        val js = """
            (function() {
                try {
                    var preBlocks = document.querySelectorAll('pre');
                    if (preBlocks.length === 0) {
                        return JSON.stringify({ text: "", isGenerating: true, hasCode: false });
                    }

                    var lastPre = preBlocks[preBlocks.length - 1];
                    var codeText = lastPre.innerText || lastPre.textContent || "";
                    
                    // حذف کلمات اضافی که ممکن است در بلوک کد باشند
                    codeText = codeText.replace(/Copy/g, "").replace(/Download/g, "").trim();

                    var isGenerating = document.querySelector('div[class*="stop"]') !== null || document.querySelector('button[aria-label*="Stop"]') !== null;
                    if (codeText.endsWith("●") || codeText.endsWith("▍")) {
                        isGenerating = true;
                    }

                    var b64 = btoa(unescape(encodeURIComponent(codeText)));
                    return JSON.stringify({ text: b64, isGenerating: isGenerating, hasCode: true });
                } catch(e) {
                    return JSON.stringify({ text: "", isGenerating: true, hasCode: false });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result != null && result != "null" && result != "\"\"") {
                val cleanJson = result.removeSurrounding("\"")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                
                try {
                    val jsonObj = org.json.JSONObject(cleanJson)
                    val hasCode = jsonObj.getBoolean("hasCode")
                    
                    if (!hasCode) {
                        handler.postDelayed({ checkAiResponse() }, 1000)
                        return@evaluateJavascript
                    }

                    val base64Text = jsonObj.getString("text")
                    val isGenerating = jsonObj.getBoolean("isGenerating")
                    
                    val decodedBytes = Base64.decode(base64Text, Base64.DEFAULT)
                    val text = String(decodedBytes, Charsets.UTF_8).trim()
                    
                    if (isGenerating || text.isEmpty()) {
                        lastAiText = ""
                        stableCounter = 0
                        handler.postDelayed({ checkAiResponse() }, 1000)
                        return@evaluateJavascript
                    }
                    
                    if (text == lastAiText) {
                        stableCounter++
                        if (stableCounter >= 2) { // ۲ ثانیه ثابت ماند
                            Logger.i("Agent", "Stable code block found. Length: ${text.length}")
                            Logger.d("Agent", "Snippet: ${text.take(200)}")
                            
                            var command = text
                            // پاک کردن مارک‌داون اگر داخل تگ pre بود
                            if (command.startsWith("```")) {
                                command = command.substringAfter("\n").substringBefore("```")
                            }
                            command = command.trim()
                            
                            if (command.isNotEmpty()) {
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
