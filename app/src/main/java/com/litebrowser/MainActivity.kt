package com.litebrowser

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var btnTerminal: Button
    private lateinit var btnReload: Button

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
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                urlBar.setText(url ?: "")
                Logger.i("Browser", "Page started: $url")
            }
        }

        // Enter در نوار آدرس → load
        urlBar.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                loadUrlOrSearch(urlBar.text.toString().trim())
                return@setOnKeyListener true
            }
            false
        }

        // دکمه ترمینال
        btnTerminal.setOnClickListener {
            Logger.i("Browser", "Opening TerminalActivity")
            startActivity(Intent(this, TerminalActivity::class.java))
        }

        // دکمه reload
        btnReload.setOnClickListener {
            webView.reload()
        }

        // شروع
        webView.loadUrl("https://www.google.com")
        Logger.i("Browser", "Initial URL loaded")
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
        Logger.i("Browser", "Loading: $url")
        webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
