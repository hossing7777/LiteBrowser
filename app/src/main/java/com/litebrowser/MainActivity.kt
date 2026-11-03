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
    private var isAiWatcherRunning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("Browser", "onCreate() called")

        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webview)
        urlBar = findViewById(R.id.url_bar)
        btnTerminal = findViewById(R.id.btn_terminal)
        btnReload = findViewById(R.id.btn_reload)

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
                loadUrlOrSearch(urlBar.text.toString().trim())
                return@setOnKeyListener true
            }
            false
        }

        btnTerminal.setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }

        btnReload.setOnClickListener { webView.reload() }

        webView.loadUrl("https://chat.deepseek.com/") // باز کردن مستقیم دیپ‌سیک
        requestStoragePermission()
        
        // ✅ شروع پایش فایل درخواست AI
        startAiWatcher()
    }

    private fun startAiWatcher() {
        val reqFile = File(filesDir, "home/ai_req.txt")
        Thread {
            while (isAiWatcherRunning) {
                if (reqFile.exists()) {
                    val prompt = reqFile.readText()
                    reqFile.delete() // پاک کردن فایل پس از خواندن
                    runOnUiThread {
                        executeAiPrompt(prompt)
                    }
                }
                Thread.sleep(500)
            }
        }.start()
    }

    private fun executeAiPrompt(prompt: String) {
        val safePrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
        
        // جاوااسکریپت برای پر کردن فیلد، ارسال، و انتظار برای دریافت پاسخ
        val js = """
            (function() {
                var ta = document.querySelector('textarea');
                if (!ta) {
                    ta = document.querySelector('div[contenteditable="true"]');
                    if (ta) {
                        ta.innerText = "$safePrompt";
                        ta.dispatchEvent(new Event('input', { bubbles: true }));
                    } else {
                        AndroidBridge.onAiError("Textarea not found. Are you logged in and on DeepSeek?");
                        return;
                    }
                } else {
                    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                    nativeSetter.call(ta, "$safePrompt");
                    ta.dispatchEvent(new Event('input', { bubbles: true }));
                }
                
                setTimeout(function() {
                    // شبیه‌سازی زدن کلید Enter
                    ta.dispatchEvent(new KeyboardEvent('keydown', {
                        key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true
                    }));
                    
                    var lastText = "";
                    var stableCount = 0;
                    var attempts = 0;
                    var interval = setInterval(function() {
                        attempts++;
                        // پیدا کردن آخرین پیام هوش مصنوعی (کلاس ds-markdown در دیپ‌سیک)
                        var msgs = document.querySelectorAll('.ds-markdown');
                        if (msgs.length > 0) {
                            var currentText = msgs[msgs.length - 1].innerText;
                            if (currentText === lastText && currentText.length > 0) {
                                stableCount++;
                                // اگر 2 ثانیه متن تغییر نکرد، یعنی پاسخ کامل شده است
                                if (stableCount >= 4) {
                                    clearInterval(interval);
                                    AndroidBridge.onAiResponse(currentText);
                                }
                            } else {
                                stableCount = 0;
                                lastText = currentText;
                            }
                        }
                        // اگر 60 ثانیه طول کشید، تایم‌اوت بده
                        if (attempts > 120) {
                            clearInterval(interval);
                            AndroidBridge.onAiError("Timeout waiting for AI response");
                        }
                    }, 500);
                }, 500);
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js, null)
        Toast.makeText(this, "سوال به هوش مصنوعی ارسال شد...", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        isAiWatcherRunning = false
        super.onDestroy()
    }

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
        } catch (e: Exception) {
            Logger.e("Browser", "requestStoragePermission failed", e)
        }
    }

    private fun loadUrlOrSearch(input: String) {
        if (input.isEmpty()) return
        var url = input
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(".") && !url.contains(" ")) {
                "https://$url"
            } else {
                "https://www.google.com/search?q=" + Uri.encode(url)
            }
        }
        webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
