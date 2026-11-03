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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("Agent", "AgentActivity onCreate started")
        setContentView(R.layout.activity_agent)
        
        etPrompt = findViewById(R.id.et_agent_prompt)
        btnStart = findViewById(R.id.btn_agent_start)
        tvLog = findViewById(R.id.tv_agent_log)
        webView = findViewById(R.id.agent_webview)
        progressBar = findViewById(R.id.agent_progress)

        // ارتفاع WebView را زیاد می‌کنیم تا دیپ‌سیک پاسخ را کاملاً رندر کند
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
    private var emptyAttempts = 0

    private fun checkAiResponse() {
        if (!isRunning) return
        aiCheckAttempts++
        
        if (aiCheckAttempts > 180) {
            log("❌ Error: Total timeout waiting for AI response.")
            Logger.e("Agent", "Total timeout waiting for AI response.")
            isRunning = false
            return
        }

        val js = """
            (function() {
                try {
                    window.scrollTo(0, document.body.scrollHeight);
                    var codeText = "";
                    var hasCode = false;
                    
                    var pres = document.querySelectorAll('pre');
                    if (pres.length > 0) {
                        var lastPre = pres[pres.length - 1];
                        codeText = lastPre.innerText || lastPre.textContent || "";
                        // حذف دکمه Copy. کلمه Download را حذف نمی‌کنیم!
                        codeText = codeText.replace(/^Copy\s*\n?/i, "").trim();
                        hasCode = true;
                    } else {
                        var markdowns = document.querySelectorAll('.ds-markdown, div[class*="markdown"]');
                        if (markdowns.length > 0) {
                            var lastMd = markdowns[markdowns.length - 1];
                            var text = lastMd.innerText || lastMd.textContent || "";
                            var match = text.match(/```(?:bash|sh|shell)?\s*([\s\S]*?)```/);
                            if (match) {
                                codeText = match[1].trim();
                                hasCode = true;
                            }
                        }
                    }
                    
                    var isGenerating = false;
                    var bodyText = document.body.innerText || "";
                    if (bodyText.includes("Thinking...") || bodyText.includes("Thought for")) {
                        isGenerating = true;
                    }
                    if (codeText.endsWith("●") || codeText.endsWith("▍") || codeText.endsWith("_")) {
                        isGenerating = true;
                    }
                    
                    var b64 = btoa(unescape(encodeURIComponent(codeText)));
                    return JSON.stringify({ text: b64, isGenerating: isGenerating, hasCode: hasCode });
                } catch(e) {
                    return JSON.stringify({ text: "", isGenerating: true, hasCode: false });
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
                val base64Text = jsonObj.getString("text")
                val isGenerating = jsonObj.getBoolean("isGenerating")
                
                val decodedBytes = Base64.decode(base64Text, Base64.DEFAULT)
                val text = String(decodedBytes, Charsets.UTF_8).trim()
                
                if (isGenerating) {
                    emptyAttempts = 0
                    handler.postDelayed({ checkAiResponse() }, 1000)
                    return@evaluateJavascript
                }
                
                if (!hasCode || text.isEmpty()) {
                    emptyAttempts++
                    if (emptyAttempts > 60) { 
                        log("❌ Error: DeepSeek did not respond with code.")
                        Logger.e("Agent", "DeepSeek did not respond with code.")
                        isRunning = false
                        return@evaluateJavascript
                    }
                    handler.postDelayed({ checkAiResponse() }, 1000)
                    return@evaluateJavascript
                }
                
                log("📥 AI response received.")
                Logger.i("Agent", "AI Code received: ${text.take(50)}...")
                aiCheckAttempts = 0
                emptyAttempts = 0
                processAiResponse(text)
                
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
            // ✅ راه‌حل جدید: نوشتن دستور در یک اسکریپت و اجرای اسکریپت
            val scriptFile = File(filesDir, "home/run_agent.sh")
            val cleanCommand = command.replace("\r\n", "\n")
            scriptFile.writeText(cleanCommand)
            
            val startMarker = "___AGENT_START_123___"
            val endMarker = "___AGENT_END_123___"
            
            // اجرای اسکریپت به جای تایپ مستقیم متن چند خطی
            val execCmd = "echo \"$startMarker\"; sh /root/run_agent.sh; echo \"$endMarker\"\r"
            
            session.write(execCmd)
            log("⏳ Executing...")
            Logger.i("Agent", "Executing script file...")
            
            handler.postDelayed({ readTerminalOutput(startMarker, endMarker, 0) }, 1500)
        } else {
            log("❌ Error: No terminal session.")
            Logger.e("Agent", "No terminal session.")
            isRunning = false
        }
    }

    private fun readTerminalOutput(startMarker: String, endMarker: String, attempts: Int) {
        if (!isRunning) return
        
        if (attempts > 60) {
            log("❌ Error: Command execution timed out.")
            Logger.e("Agent", "Command execution timed out after 60s.")
            isRunning = false
            return
        }

        val session = TerminalManager.sessions.firstOrNull()
        val screenText = session?.emulator?.screen?.getTranscriptText() ?: ""

        if (screenText.contains(endMarker)) {
            val startIndex = screenText.indexOf(startMarker)
            val endIndex = screenText.indexOf(endMarker)
            
            var output = if (startIndex != -1 && endIndex != -1) {
                screenText.substring(startIndex + startMarker.length, endIndex).trim()
            } else {
                "Command executed (no output captured)"
            }

            if (output.length > 1500) {
                output = output.take(1500) + "\n... (output truncated)"
            }

            log("\n📄 Output:\n$output\n")
            Logger.i("Agent", "Output captured. Sending back to AI.")

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
        Logger.i("Agent", "AgentActivity onDestroy")
        super.onDestroy()
    }
}
