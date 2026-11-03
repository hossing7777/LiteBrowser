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
            session.write("$command\r".toByteArray())
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
                val base64Text = android.util.Base64.encodeToString(cleanText?.toByteArray(), android.util.Base64.NO_WRAP)
                session.write("echo '$base64Text' | base64 -d > /root/scraped.txt\r".toByteArray())
                Toast.makeText(context, "محتوای صفحه در /root/scraped.txt ذخیره شد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun onAiResponse(text: String) {
        try {
            val resFile = File(context.filesDir, "home/ai_res.txt")
            resFile.writeText(text)
        } catch (e: Exception) {
            Logger.e("WebAppInterface", "Failed to write AI response", e)
        }
    }

    @JavascriptInterface
    fun onAiError(error: String) {
        try {
            val resFile = File(context.filesDir, "home/ai_res.txt")
            resFile.writeText("Error: $error")
        } catch (e: Exception) {
            Logger.e("WebAppInterface", "Failed to write AI error", e)
        }
    }
}
