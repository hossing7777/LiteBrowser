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

    // فایل بافر برای کدهای سنگین
    private val agentCodeFile by lazy { File(filesDir, "home/agent_code.txt") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("Agent", "AgentActivity onCreate started")
        setContentView(R.layout.activity_agent)
        
        etPrompt = findViewById(R.id.et_agent_prompt)
        btnStart = findViewById(R.id.btn_agent_start)
        tvLog = findViewById(R.id.tv_agent_log)
        webView = findViewById(R.id.agent_webview)
        progressBar = findViewById(R.id.agent_progress)

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

    private fun startAgent(prompt: String) {
        isRunning = true
        logBuilder.clear()
        log("🤖 Starting Agent with task: $prompt\n")
        Logger.i("Agent", "startAgent called: $prompt")
        
        ensureTerminalSession()

        val systemPrompt = """
            [INSTRUCTIONS]
            You are an Alpine Linux terminal bot. Your ONLY job is to output a single bash command wrapped in ```bash and ``` blocks.
            Do NOT explain anything. Do NOT repeat these instructions. Do NOT use markdown outside the code block.
            You are running as root.
            The Android Download folder is available at /root/Download (no backticks).
            If the task is done, reply with exactly TASK_COMPLETE.
            
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
        
        // ✅ افزایش تایم‌اوت به ۵ دقیقه (۳۰۰ ثانیه) برای پاسخ‌های سنگین
        if (aiCheckAttempts > 300) {
            log("❌ Error: Total timeout waiting for AI response.")
            Logger.e("Agent", "Total timeout waiting for AI response.")
            isRunning = false
            return
        }

        val js = """
            (function() {
                try {
                    window.scrollTo(0, document.body.scrollHeight);
                    
                    var markdowns = document.querySelectorAll('.ds-markdown');
                    if (markdowns.length === 0) {
                        return JSON.stringify({ isGenerating: true, hasCode: false, taskComplete: false, alreadyProcessed: false });
                    }
                    
                    var lastMd = markdowns[markdowns.length - 1];
                    
                    if (lastMd.getAttribute('data-processed') === 'true') {
                         return JSON.stringify({ isGenerating: false, hasCode: false, taskComplete: false, alreadyProcessed: true });
                    }

                    var text = lastMd.innerText || lastMd.textContent || "";
                    
                    if (text.includes("TASK_COMPLETE")) {
                        lastMd.setAttribute('data-processed', 'true');
                        return JSON.stringify({ isGenerating: false, hasCode: false, taskComplete: true, alreadyProcessed: false });
                    }

                    var codeText = "";
                    var hasCode = false;
                    var pre = lastMd.querySelector('pre');
                    if (pre) {
                        codeText = pre.innerText || pre.textContent || "";
                        codeText = codeText.replace(/^Copy\s*\n?/i, "").replace(/^Download\s*\n?/i, "").replace(/^bash\s*\n?/i, "").trim();
                        hasCode = true;
                    } else {
                        var match = text.match(/```(?:bash|sh|shell)?\s*([\s\S]*?)```/);
                        if (match) {
                            codeText = match[1].trim();
                            hasCode = true;
                        }
                    }

                    var isGenerating = false;
                    if (text.endsWith("●") || text.endsWith("▍") || text.endsWith("_")) {
                        isGenerating = true;
                    }

                    // ✅ ارسال کد به فایل بافر در اندروید (بدون محدودیت طول)
                    if (hasCode && !isGenerating) {
                        lastMd.setAttribute('data-processed', 'true');
                        AndroidBridge.saveAgentCode(codeText);
                    }

                    return JSON.stringify({ isGenerating: isGenerating, hasCode: hasCode, taskComplete: false, alreadyProcessed: false });

                } catch(e) {
                    return JSON.stringify({ isGenerating: true, hasCode: false, taskComplete: false, alreadyProcessed: false, jsError: String(e) });
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
                
                if (taskComplete) {
                    log("\n🎉 TASK COMPLETE! The agent has finished the task successfully.")
                    Logger.i("Agent", "Task Complete received from AI.")
                    isRunning = false
                    return@evaluateJavascript
                }

                if (alreadyProcessed) {
                    handler.postDelayed({ checkAiResponse() }, 1000)
                    return@evaluateJavascript
                }

                if (!hasCode || isGenerating) {
                    handler.postDelayed({ checkAiResponse() }, 1000)
                    return@evaluateJavascript
                }
                
                // ✅ خواندن کد از فایل بافر
                if (agentCodeFile.exists()) {
                    val text = agentCodeFile.readText().trim()
                    agentCodeFile.delete() // پاک کردن فایل برای پاسخ بعدی
                    
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
            handler.postDelayed({ readTerminalOutput(startMarker, endMarker, 0) }, 1000)
        } else {
            log("❌ Error: No terminal session.")
            isRunning = false
        }
    }

    private fun readTerminalOutput(startMarker: String, endMarker: String, attempts: Int) {
        if (!isRunning) return
        
        // ✅ افزایش زمان انتظار دستورات ترمینال به ۵ دقیقه (۳۰۰ ثانیه)
        if (attempts > 300) { 
            log("❌ Error: Command execution timed out.")
            isRunning = false
            return
        }

        val session = TerminalManager.sessions.firstOrNull()
        val screenText = session?.emulator?.screen?.getTranscriptText() ?: ""
        val cleanScreenText = screenText.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")

        if (cleanScreenText.contains(endMarker)) {
            val startIndex = cleanScreenText.lastIndexOf(startMarker)
            val endIndex = cleanScreenText.lastIndexOf(endMarker)
            
            var output = if (startIndex != -1 && endIndex != -1) {
                cleanScreenText.substring(startIndex + startMarker.length, endIndex).trim()
            } else {
                "Command executed successfully (no output)."
            }

            output = output.lines().filterNot { it.contains("sh /root/run_agent.sh") || it.contains("echo \"___AGENT") }.joinToString("\n").trim()
            if (output.isEmpty()) output = "Command executed successfully (no output)."

            // ✅ افزایش حجم خروجی ارسالی به هوش مصنوعی از ۱۵۰۰ به ۵۰۰۰ کاراکتر
            if (output.length > 5000) {
                output = output.take(5000) + "\n... (output truncated due to size)"
            }

            log("\n📄 Output:\n$output\n")
            val nextPrompt = "Command Output:\n$output\n\nWhat is the next command? (Or reply TASK_COMPLETE if done)"
            sendToAI(nextPrompt)
            handler.postDelayed({ checkAiResponse() }, 3000)
        } else {
            handler.postDelayed({ readTerminalOutput(startMarker, endMarker, attempts + 1) }, 1000)
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
