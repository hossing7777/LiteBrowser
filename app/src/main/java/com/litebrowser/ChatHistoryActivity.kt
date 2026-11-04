package com.litebrowser

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import java.io.File

class ChatHistoryActivity : Activity() {

    private lateinit var lvChats: ListView
    private var chatFiles: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_history)

        lvChats = findViewById(R.id.lv_chats)
        loadChats()
        
        lvChats.setOnItemClickListener { _, _, position, _ ->
            val selectedChat = chatFiles[position]
            showChatDetails(selectedChat)
        }
    }

    private fun loadChats() {
        chatFiles = ChatManager.getAllChats()
        
        val adapter = object : ArrayAdapter<File>(this, android.R.layout.simple_list_item_1, chatFiles) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.text = chatFiles[position].name.replace("_", " ")
                textView.textSize = 16f
                textView.setPadding(16, 24, 16, 24)
                return view
            }
        }
        
        lvChats.adapter = adapter
    }

    private fun showChatDetails(chatFolder: File) {
        val messages = ChatManager.loadChatHistory(chatFolder)
        val sb = StringBuilder()
        
        for (msg in messages) {
            when(msg.role) {
                "user" -> sb.append("👤 YOU:\n${msg.content}\n\n")
                "ai" -> sb.append("🤖 AI:\n${msg.content}\n\n")
                "terminal_output" -> sb.append("💻 TERMINAL:\n${msg.content}\n\n")
            }
        }
        
        if (sb.isEmpty()) {
            sb.append("No messages saved yet.")
        }

        AlertDialog.Builder(this)
            .setTitle(chatFolder.name)
            .setMessage(sb.toString())
            .setPositiveButton("Close", null)
            .show()
    }
}
