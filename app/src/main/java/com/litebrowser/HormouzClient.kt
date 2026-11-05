package com.litebrowser

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object HormouzClient {
    private const val TAG = "HormouzClient"
    private const val BASE_URL = "https://api.hormouz.net/v1/chat/completions"

    data class Message(val role: String, val content: String)
    data class ApiResponse(val success: Boolean, val content: String?, val error: String?)

    fun sendRequest(
        apiKey: String,
        model: String,
        messages: List<Message>,
        temperature: Double = 0.7,
        maxTokens: Int = 4096
    ): ApiResponse {
        if (apiKey.isBlank()) return ApiResponse(false, null, "API Key is empty")
        
        try {
            val url = URL(BASE_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000

            val messagesArray = JSONArray()
            for (msg in messages) {
                val msgObj = JSONObject()
                msgObj.put("role", msg.role)
                msgObj.put("content", msg.content)
                messagesArray.put(msgObj)
            }

            val jsonBody = JSONObject()
            jsonBody.put("model", model)
            jsonBody.put("messages", messagesArray)
            jsonBody.put("temperature", temperature)
            jsonBody.put("max_tokens", maxTokens)
            jsonBody.put("stream", false)

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val responseStr = reader.readText()
                reader.close()
                conn.disconnect()

                val jsonResponse = JSONObject(responseStr)
                val choices = jsonResponse.getJSONArray("choices")
                if (choices.length() > 0) {
                    val content = choices.getJSONObject(0).getJSONObject("message").getString("content")
                    return ApiResponse(true, content, null)
                }
                return ApiResponse(false, null, "Empty response from API")
            } else {
                val errorStream = conn.errorStream
                val errorStr = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).readText()
                } else "HTTP $responseCode"
                conn.disconnect()
                return ApiResponse(false, null, "API Error ($responseCode): $errorStr")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed", e)
            return ApiResponse(false, null, e.message ?: "Unknown error")
        }
    }

    // لیست مدل‌های پیشنهادی هرمز
    val AVAILABLE_MODELS = listOf(
        // مدل‌های پولی (بدون محدودیت روزانه - هزینه ناچیز)
        "nvidia/nemotron-3.5-lightning",
        "nvidia/nemotron-3-ultra-550b-a55b",
        "google/gemma-4-31b-it",
        "google/gemma-4-26b-a4b-it",
        "liquid/lfm-2.5-2.6b",
        "xingchen-agi/xing4.0-29b-a4b",
        "recursive/flint-v1.0",
        // مدل‌های رایگان (محدودیت روزانه)
        "nvidia/nemotron-3.5-lightning:free",
        "nvidia/nemotron-3-ultra-550b-a55b:free",
        "google/gemma-4-31b-it:free",
        "google/gemma-4-26b-a4b-it:free",
        "liquid/lfm-2.5-2.6b:free",
        "xingchen-agi/xing4.0-29b-a4b:free",
        "recursive/flint-v1.0:free"
    )
}
