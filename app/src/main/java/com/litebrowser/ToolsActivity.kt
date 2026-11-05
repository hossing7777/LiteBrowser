package com.litebrowser

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

class ToolsActivity : Activity() {

    private lateinit var etKeywords: EditText
    private lateinit var btnSaveKeywords: Button
    private lateinit var btnRefreshTools: Button
    private lateinit var lvTools: ListView
    private lateinit var tvNoTools: TextView

    private val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Local_AI_Models")
    private val keywordsFile = File(baseDir, "tools_keywords.txt")
    private val toolsDir = File(baseDir, "Tools_app")

    private val toolFolders = mutableListOf<File>()
    private val toolNames = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools)

        etKeywords = findViewById(R.id.et_keywords)
        btnSaveKeywords = findViewById(R.id.btn_save_keywords)
        btnRefreshTools = findViewById(R.id.btn_refresh_tools)
        lvTools = findViewById(R.id.lv_tools)
        tvNoTools = findViewById(R.id.tv_no_tools)

        baseDir.mkdirs()
        toolsDir.mkdirs()

        loadKeywords()
        setupListView()

        btnSaveKeywords.setOnClickListener {
            try {
                keywordsFile.writeText(etKeywords.text.toString())
                Toast.makeText(this, "Keywords saved successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error saving keywords: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        btnRefreshTools.setOnClickListener {
            loadTools()
            Toast.makeText(this, "Tools refreshed!", Toast.LENGTH_SHORT).show()
        }

        loadTools()
    }

    private fun loadKeywords() {
        if (keywordsFile.exists()) {
            try {
                etKeywords.setText(keywordsFile.readText())
            } catch (e: Exception) {}
        }
    }

    private fun setupListView() {
        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, toolNames) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val tv = view.findViewById<TextView>(android.R.id.text1)
                tv.textSize = 14f
                tv.setPadding(16, 24, 16, 24)
                tv.typeface = android.graphics.Typeface.MONOSPACE
                return view
            }
        }
        lvTools.adapter = adapter
        lvTools.setOnItemClickListener { _, _, position, _ ->
            showToolDetails(toolFolders[position])
        }
    }

    private fun loadTools() {
        toolFolders.clear()
        toolNames.clear()

        val folders = toolsDir.listFiles { file -> file.isDirectory } ?: emptyList()
        val sortedFolders = folders.sortedByDescending { it.name }

        for (folder in sortedFolders) {
            toolFolders.add(folder)
            val infoFile = File(folder, "info.txt")
            var isReady = false
            if (infoFile.exists()) {
                try {
                    val lines = infoFile.readLines()
                    if (lines.size >= 3 && lines[2].trim().equals("READY", ignoreCase = true)) {
                        isReady = true
                    }
                } catch (e: Exception) {}
            }
            val statusIcon = if (isReady) "[READY] " else "[TESTING] "
            toolNames.add(statusIcon + folder.name)
        }

        adapter.notifyDataSetChanged()
        tvNoTools.visibility = if (toolFolders.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showToolDetails(folder: File) {
        val infoFile = File(folder, "info.txt")
        var faDesc = "No Persian description."
        var enDesc = "No English description."
        var status = "TESTING"

        if (infoFile.exists()) {
            try {
                val lines = infoFile.readLines()
                if (lines.isNotEmpty()) faDesc = lines[0]
                if (lines.size > 1) enDesc = lines[1]
                if (lines.size > 2) status = lines[2]
            } catch (e: Exception) {}
        }

        val statusColor = if (status.trim().equals("READY", ignoreCase = true)) "#4CAF50" else "#F44336"
        val statusText = if (status.trim().equals("READY", ignoreCase = true)) "Green Light (Ready)" else "Red Light (Testing)"

        val message = "Folder: ${folder.name}\n\n" +
                "Persian (FA):\n$faDesc\n\n" +
                "English (EN):\n$enDesc\n\n" +
                "Status: $statusText"

        val builder = AlertDialog.Builder(this)
            .setTitle("Tool Details")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .setNegativeButton("Delete Tool") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Confirm Delete")
                    .setMessage("Are you sure you want to delete '${folder.name}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        if (folder.deleteRecursively()) {
                            Toast.makeText(this, "Tool deleted", Toast.LENGTH_SHORT).show()
                            loadTools()
                        } else {
                            Toast.makeText(this, "Failed to delete", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

        val dialog = builder.create()
        dialog.show()
        
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#F44336"))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor(statusColor))
    }
}
