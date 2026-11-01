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

        webView.loadUrl("https://www.google.com")

        // درخواست دسترسی storage
        requestStoragePermission()
    }

    private fun requestStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ → MANAGE_EXTERNAL_STORAGE
                if (!Environment.isExternalStorageManager()) {
                    Logger.i("Browser", "Requesting MANAGE_EXTERNAL_STORAGE")
                    Toast.makeText(this, "برای دسترسی به فایل‌ها، به تنظیمات بروید", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            } else {
                // Android 10 و پایین‌تر
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        100
                    )
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
