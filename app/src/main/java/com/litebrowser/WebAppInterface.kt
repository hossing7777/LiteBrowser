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
        }
    }

    @JavascriptInterface
    fun logDebug(msg: String) {
        Logger.i("AgentBridge", msg)
    }

    @JavascriptInterface
    fun onAiResponse(text: String) {
        try {
            val resFile = File(context.filesDir, "home/ai_res.txt")
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

    @JavascriptInterface
    fun onAgentError(error: String) {
        Logger.e("Agent", "JS Error: $error")
        Toast.makeText(context, "Agent Error: $error", Toast.LENGTH_LONG).show()
    }
}
