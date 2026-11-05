package com.litebrowser

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object ApiClient {
    // آدرس API هرمز (سازگار با OpenAI)
    private const val BASE_URL = "https://hormouz.net/v1/chat/completions"
    
    fun sendPrompt(context: Context, apiKey: String, model: String, prompt: String, onResponse: (String) -> Unit, onError: (String) -> Unit) {
        thread {
            try {
                val url = URL(BASE_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true
                conn.connectTimeout = 60000
                conn.readTimeout = 60000

                // ساخت بدنه JSON (به صورت دستی برای جلوگیری از نیاز به کتابخانه اضافی)
                val escapedPrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                val jsonBody = """
                    {
                        "model": "$model",
                        "messages": [{"role": "user", "content": "$escapedPrompt"}],
                        "stream": false
                    }
                """.trimIndent()

                val os = OutputStreamWriter(conn.outputStream)
                os.write(jsonBody)
                os.flush()
                os.close()

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    
                    // استخراج محتوای پاسخ (Parsing ساده)
                    val jsonString = response.toString()
                    val contentMatch = Regex("\"content\"\\s*:\\s*\"(.*?)\"").find(jsonString)
                    val content = contentMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "Error parsing response"
                    onResponse(content)
                } else {
                    val errorStream = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    onError("HTTP $responseCode: $errorStream")
                }
                conn.disconnect()
            } catch (e: Exception) {
                onError("Network Error: ${e.message}")
            }
        }
    }
}
