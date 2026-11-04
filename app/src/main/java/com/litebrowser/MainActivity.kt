package com.litebrowser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var btnTerminal: Button
    private lateinit var btnReload: Button
    private lateinit var btnAgent: Button
    private lateinit var btnFiles: Button
    private var isAiWatcherRunning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("Browser", "onCreate() called")
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webview)
        urlBar = findViewById(R.id.url_bar)
        btnTerminal = findViewById(R.id.btn_terminal)
        btnReload = findViewById(R.id.btn_reload)
        btnAgent = findViewById(R.id.btn_agent)
        btnFiles = findViewById(R.id.btn_files)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(WebAppInterface(this, webView), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                urlBar.setText(url ?: "")
            }
        }

        urlBar.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                loadUrlOrSearch(urlBar.text.toString().trim()); true
            } else false
        }

        btnAgent.setOnClickListener { startActivity(Intent(this, AgentActivity::class.java)) }
        btnTerminal.setOnClickListener { startActivity(Intent(this, TerminalActivity::class.java)) }
        btnFiles.setOnClickListener { startActivity(Intent(this, FileManagerActivity::class.java)) }
        btnReload.setOnClickListener { webView.reload() }

        webView.loadUrl("https://chat.deepseek.com/")
        requestStoragePermission()
        startAiWatcher()
    }

    private fun startAiWatcher() {
        val reqFile = File(filesDir, "home/ai_req.txt")
        Thread {
            while (isAiWatcherRunning) {
                if (reqFile.exists()) {
                    val base64Prompt = reqFile.readText().trim()
                    val prompt = try {
                        String(android.util.Base64.decode(base64Prompt, android.util.Base64.NO_WRAP))
                    } catch (e: Exception) { base64Prompt }
                    reqFile.delete()
                    runOnUiThread { executeAiPrompt(prompt) }
                }
                try { Thread.sleep(500) } catch (e: InterruptedException) { break }
            }
        }.start()
    }

    private fun executeAiPrompt(prompt: String) {
        val safePrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
        val js = """
            (function() {
                var ta = document.querySelector('textarea');
                if (!ta) {
                    AndroidBridge.onAiError("Textarea not found. Are you logged in and on the chat page?");
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
                            if (rect.left > taRect.left + (taRect.width / 2)) { btn.click(); break; }
                        }
                    }
                    var lastText = "";
                    var stableCount = 0;
                    var attempts = 0;
                    var interval = setInterval(function() {
                        attempts++;
                        var msgs = document.querySelectorAll('.ds-markdown');
                        if (msgs.length > 0) {
                            var clone = msgs[msgs.length - 1].cloneNode(true);
                            var btns = clone.querySelectorAll('button, .ds-icon-button');
                            for(var j=0; j<btns.length; j++) { btns[j].remove(); }
                            var currentText = clone.innerText;
                            if (currentText === lastText && currentText.length > 0) {
                                stableCount++;
                                if (stableCount >= 4) {
                                    clearInterval(interval);
                                    AndroidBridge.onAiResponse(currentText);
                                }
                            } else { stableCount = 0; lastText = currentText; }
                        }
                        if (attempts > 600) { clearInterval(interval); AndroidBridge.onAiError("Timeout waiting for AI response"); }
                    }, 500);
                }, 500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        Toast.makeText(this, "سوال به هوش مصنوعی ارسال شد...", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() { isAiWatcherRunning = false; super.onDestroy() }

    private fun requestStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
                }
            }
        } catch (e: Exception) { Logger.e("Browser", "requestStoragePermission failed", e) }
    }

    private fun loadUrlOrSearch(input: String) {
        if (input.isEmpty()) return
        var url = input
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(".") && !url.contains(" ")) "https://$url"
            else "https://www.google.com/search?q=" + Uri.encode(url)
        }
        webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
