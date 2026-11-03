package com.litebrowser

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import java.io.File

class WebAppInterface(private val context: Context, private val webView: WebView) {

    @JavascriptInterface
    fun runTerminalCommand(command: String) {
        val session = TerminalManager.sessions.firstOrNull()
        if (session != null && session.isRunning) {
            session.write("$command\r")
        } else {
            Toast.makeText(context, "هیچ جلسه ترمینالی فعال نیست!", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun scrapeAndSendToTerminal(selector: String) {
        webView.evaluateJavascript("(function() { return document.querySelector('$selector')?.innerText || ''; })()") { result ->
            val cleanText = result?.replace("\"", "")?.replace("\\n", "\n")
            val session = TerminalManager.sessions.firstOrNull()
            if (session != null && session.isRunning) {
                val base64Text = android.util.Base64.encodeToString(cleanText?.toByteArray() ?: ByteArray(0), android.util.Base64.NO_WRAP)
                session.write("echo '$base64Text' | base64 -d > /root/scraped.txt\r")
                Toast.makeText(context, "محتوای صفحه در /root/scraped.txt ذخیره شد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun onAiResponse(text: String) {
        try {
            val resFile = File(context.filesDir, "home/ai_res.txt")
            // ✅ استفاده از android.util.Base64
            val base64Text = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            resFile.writeText(base64Text)
        } catch (e: Exception) {
            Logger.e("WebAppInterface", "Failed to write AI response", e)
        }
    }

    @JavascriptInterface
    fun onAiError(error: String) {
        try {
            val resFile = File(context.filesDir, "home/ai_res.txt")
            val base64Text = android.util.Base64.encodeToString("Error: $error".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            resFile.writeText(base64Text)
        } catch (e: Exception) {
            Logger.e("WebAppInterface", "Failed to write AI error", e)
        }
    }
}
