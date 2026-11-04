package com.litebrowser

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val role: String, // "user", "ai", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

object ChatManager {
    private val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Local_AI_Models/Agent_Chats")

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    fun sanitizeFileName(input: String): String {
        var clean = input.replace("\n", " ").replace("\r", "")
        // فقط حروف انگلیسی، اعداد و کاراکترهای مجاز را نگه می‌داریم
        clean = clean.replace(Regex("[^a-zA-Z0-9_\\-\\u0600-\\u06FF]"), "_")
        if (clean.length > 40) clean = clean.substring(0, 40)
        return clean.trim('_')
    }

    fun createNewChat(prompt: String): File {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
        val timestamp = dateFormat.format(Date())
        val safeTitle = sanitizeFileName(prompt)
        val folderName = "${timestamp}_${safeTitle}"
        
        val chatFolder = File(baseDir, folderName)
        if (!chatFolder.exists()) chatFolder.mkdirs()
        
        // ایجاد یک فایل خالی برای شروع
        saveChatHistory(chatFolder, emptyList())
        return chatFolder
    }

    fun saveChatHistory(chatFolder: File, messages: List<ChatMessage>) {
        try {
            val jsonArray = JSONArray()
            for (msg in messages) {
                val jsonObj = JSONObject()
                jsonObj.put("role", msg.role)
                jsonObj.put("content", msg.content)
                jsonObj.put("timestamp", msg.timestamp)
                jsonArray.put(jsonObj)
            }
            val file = File(chatFolder, "chat_history.json")
            file.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            Logger.e("ChatManager", "Failed to save chat history", e)
        }
    }

    fun loadChatHistory(chatFolder: File): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        try {
            val file = File(chatFolder, "chat_history.json")
            if (file.exists()) {
                val jsonArray = JSONArray(file.readText())
                for (i in 0 until jsonArray.length()) {
                    val jsonObj = jsonArray.getJSONObject(i)
                    messages.add(ChatMessage(
                        jsonObj.getString("role"),
                        jsonObj.getString("content"),
                        jsonObj.getLong("timestamp")
                    ))
                }
            }
        } catch (e: Exception) {
            Logger.e("ChatManager", "Failed to load chat history", e)
        }
        return messages
    }

    fun getAllChats(): List<File> {
        return baseDir.listFiles { file -> file.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
    }
}
