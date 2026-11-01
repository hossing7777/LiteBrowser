package com.litebrowser

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("Browser", "onCreate() called")

        // Layout ساده‌ی مرورگر
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webview)
        urlBar = findViewById(R.id.url_bar)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                urlBar.setText(url ?: "")
                Logger.i("Browser", "Page started: $url")
            }
        }

        urlBar.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                var url = urlBar.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    if (url.contains(".") && !url.contains(" ")) {
                        url = "https://$url"
                    } else {
                        url = "https://www.google.com/search?q=" + Uri.encode(url)
                    }
                }
                Logger.i("Browser", "Loading: $url")
                webView.loadUrl(url)
                return@setOnKeyListener true
            }
            false
        }

        // شروع با Google
        webView.loadUrl("https://www.google.com")
        Logger.i("Browser", "Initial URL loaded")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_TERMINAL, 0, "Terminal").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_RELOAD, 1, "Reload")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_TERMINAL -> {
                Logger.i("Browser", "Opening TerminalActivity")
                startActivity(Intent(this, TerminalActivity::class.java))
                true
            }
            MENU_RELOAD -> {
                webView.reload()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val MENU_TERMINAL = 1001
        private const val MENU_RELOAD = 1002
    }
}
