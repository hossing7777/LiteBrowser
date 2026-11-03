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
        Logger.i("Agent", "Layout inflated")

        etPrompt = findViewById(R.id.et_agent_prompt)
        btnStart = findViewById(R.id.btn_agent_start)
        tvLog = findViewById(R.id.tv_agent_log)
        webView = findViewById(R.id.agent_webview)
        progressBar = findViewById(R.id.agent_progress)

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
                    log("✅ DeepSeek page loaded. Ready to start.")
                }
            }
        } catch (e: Exception) {
            Logger.e("Agent", "Failed to setup WebView", e)
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
            4. To create files, use `cat << 'EOF' > filename` or echo.
            
            User Task: $prompt
        """.trimIndent()

        sendToAI(systemPrompt)
        handler.postDelayed({ checkAiResponse() }, 3000)
    }

    private fun sendToAI(text: String) {
        val safePrompt = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
        
        val js = """
            (function() {
                var ta = document.querySelector('textarea');
                if (!ta) {
                    AndroidBridge.onAgentError("Textarea not found. Please make sure you are logged in on the bottom screen.");
                    return;
                }
                ta.focus();
                document.execCommand('selectAll', false, null);
                document.execCommand('insertText', false, "$safePrompt");
                
                setTimeout(function() {
                    ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
                    var buttons = document.querySelectorAll('button, div[role="button"]');
                    for (var i = buttons.length - 1; i >= 0; i--) {
                        var btn = buttons[i];
                        if (btn.querySelector('svg') && btn.offsetWidth > 0) {
                            var rect = btn.getBoundingClientRect();
                            var taRect = ta.getBoundingClientRect();
                            if (rect.left > taRect.left + (taRect.width / 2)) {
                                btn.click();
                                break;
                            }
                        }
                    }
                }, 500);
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js, null)
    }

    private var lastAiText = ""
    private fun isResponseStable(text: String): Boolean {
        if (text == lastAiText) {
            lastAiText = ""
            return true
        }
        lastAiText = text
        return false
    }

    private fun checkAiResponse() {
        if (!isRunning) return

        val js = """
            (function() {
                var msgs = document.querySelectorAll('.ds-markdown');
                if (msgs.length > 0) {
                    var clone = msgs[msgs.length - 1].cloneNode(true);
                    var btns = clone.querySelectorAll('button, .ds-icon-button');
                    for(var j=0; j<btns.length; j++) { btns[j].remove(); }
                    return clone.innerText;
                }
                return "";
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result != null && result != "null" && result != "\"\"") {
                val cleanText = result.replace("\\n", "\n").replace("\\\"", "\"").replace("\"", "")
                if (cleanText.isNotEmpty() && cleanText != "TASK_COMPLETE") {
                    if (cleanText.length > 10) {
                        if (isResponseStable(cleanText)) {
                            processAiResponse(cleanText)
                        } else {
                            handler.postDelayed({ checkAiResponse() }, 1000)
                        }
                    } else {
                        handler.postDelayed({ checkAiResponse() }, 1000)
                    }
                } else if (cleanText == "TASK_COMPLETE") {
                    log("\n✅ Agent finished the task!")
                    isRunning = false
                } else {
                    handler.postDelayed({ checkAiResponse() }, 1000)
                }
            } else {
                handler.postDelayed({ checkAiResponse() }, 1000)
            }
        }
    }

    private fun processAiResponse(aiText: String) {
        val regex = Regex("```(?:bash|sh)?\\n([\\s\\S]*?)```")
        val match = regex.find(aiText)
        
        if (match != null) {
            val command = match.groupValues[1].trim()
            log("\n💻 Running Command:\n$command\n")
            
            val session = TerminalManager.sessions.firstOrNull()
            if (session != null && session.isRunning) {
                val execCmd = "$command > /root/agent_out.txt 2>&1; echo '##DONE##' >> /root/agent_out.txt\r"
                session.write(execCmd)
                handler.postDelayed({ readTerminalOutput(aiText) }, 1500)
            } else {
                log("❌ Error: No active terminal session. Please open terminal first.")
                isRunning = false
            }
        } else {
            log("⚠️ AI did not return a valid command block. Retrying...")
            handler.postDelayed({ checkAiResponse() }, 2000)
        }
    }

    private fun readTerminalOutput(previousAiText: String) {
        if (!isRunning) return

        thread {
            if (outFile.exists()) {
                val content = outFile.readText()
                if (content.contains("##DONE##")) {
                    val output = content.replace("##DONE##", "").trim()
                    log("\n📄 Output:\n$output\n")
                    outFile.delete() 
                    
                    val nextPrompt = "Command Output:\n$output\n\nWhat is the next command? (Or reply TASK_COMPLETE if done)"
                    runOnUiThread { sendToAI(nextPrompt) }
                    handler.postDelayed({ checkAiResponse() }, 3000)
                } else {
                    handler.postDelayed({ readTerminalOutput(previousAiText) }, 1000)
                }
            } else {
                handler.postDelayed({ readTerminalOutput(previousAiText) }, 1000)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
