package com.litebrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.*

class FileManagerActivity : Activity() {

    private lateinit var lvFiles: ListView
    private lateinit var tvPath: TextView
    private lateinit var tvEmpty: TextView
    private val downloadsRoot: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    private var currentDir: File = downloadsRoot
    private val files = mutableListOf<File>()
    private val displayItems = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_manager)

        tvPath = findViewById(R.id.tv_fm_path)
        lvFiles = findViewById(R.id.lv_fm_files)
        tvEmpty = findViewById(R.id.tv_fm_empty)

        findViewById<Button>(R.id.btn_fm_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_fm_up).setOnClickListener { goUp() }
        findViewById<Button>(R.id.btn_fm_refresh).setOnClickListener { loadFiles() }

        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayItems) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.findViewById<TextView>(android.R.id.text1).apply {
                    textSize = 13f
                    setPadding(16, 20, 16, 20)
                    typeface = Typeface.MONOSPACE
                }
                return v
            }
        }
        lvFiles.adapter = adapter

        lvFiles.setOnItemClickListener { _, _, pos, _ ->
            val file = files[pos]
            if (file.isDirectory) { currentDir = file; loadFiles() } else openFile(file)
        }
        lvFiles.setOnItemLongClickListener { _, _, pos, _ -> showFileOptions(files[pos]); true }

        if (!downloadsRoot.exists()) downloadsRoot.mkdirs()
        loadFiles()
    }

    private fun loadFiles() {
        files.clear(); displayItems.clear()
        val list = currentDir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()
        files.addAll(list)
        list.forEach { f ->
            val icon = when {
                f.isDirectory -> "📁"
                f.extension.lowercase() in listOf("txt","md","log","json","py","sh","kt","java","xml","yml","yaml","conf","cfg","html","css","js","c","cpp","h","rs","go","rb","php","sql","csv") -> "📄"
                f.extension.lowercase() in listOf("jpg","jpeg","png","gif","bmp","webp") -> "🖼️"
                f.extension.lowercase() in listOf("mp3","wav","ogg","m4a") -> "🎵"
                f.extension.lowercase() in listOf("mp4","mkv","avi","mov") -> "🎬"
                f.extension.lowercase() in listOf("zip","tar","gz","xz","bz2","7z","rar") -> "📦"
                f.extension.lowercase() == "apk" -> "📱"
                else -> "📎"
            }
            val size = if (f.isDirectory) "<dir>" else formatSize(f.length())
            displayItems.add("$icon  ${f.name}\n      $size")
        }
        adapter.notifyDataSetChanged()
        tvPath.text = currentDir.absolutePath
        tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun goUp() {
        val parent = currentDir.parentFile ?: return
        if (!parent.canRead()) { Toast.makeText(this, "Cannot read parent", Toast.LENGTH_SHORT).show(); return }
        currentDir = parent; loadFiles()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024L * 1024 * 1024)} GB"
    }

    private fun isTextFile(f: File): Boolean = f.extension.lowercase() in listOf(
        "txt","md","log","json","py","sh","kt","java","xml","yml","yaml","conf","cfg",
        "html","css","js","c","cpp","h","rs","go","rb","php","sql","csv","ini","toml"
    )
    private fun isImageFile(f: File): Boolean = f.extension.lowercase() in listOf("jpg","jpeg","png","gif","bmp","webp")

    private fun openFile(f: File) {
        when {
            isTextFile(f) -> showText(f)
            isImageFile(f) -> showImage(f)
            else -> showOpenWith(f)
        }
    }

    private fun showText(f: File) {
        try {
            val maxSize = 1024 * 1024
            val content = if (f.length() > maxSize) {
                f.inputStream().use { stream ->
                    val buf = ByteArray(500 * 1024)
                    val n = stream.read(buf)
                    String(buf, 0, n) + "\n\n... (truncated, file is ${formatSize(f.length())})"
                }
            } else f.readText()

            val scroll = ScrollView(this)
            val tv = TextView(this)
            tv.text = content
            tv.setTextIsSelectable(true)
            tv.textSize = 11f
            tv.typeface = Typeface.MONOSPACE
            tv.setPadding(20, 20, 20, 20)
            scroll.addView(tv)

            AlertDialog.Builder(this)
                .setTitle(f.name)
                .setView(scroll)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy All") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(f.name, content))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showImage(f: File) {
        try {
            if (f.length() > 20L * 1024 * 1024) {
                Toast.makeText(this, "Image too large (>20MB)", Toast.LENGTH_LONG).show(); return
            }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            val maxDim = 2048
            if (opts.outWidth > maxDim || opts.outHeight > maxDim) {
                opts.inSampleSize = maxOf(opts.outWidth / maxDim, opts.outHeight / maxDim, 1)
            }
            opts.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opts)
            if (bmp == null) { Toast.makeText(this, "Cannot load image", Toast.LENGTH_SHORT).show(); return }
            val iv = ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true; setPadding(20, 20, 20, 20) }
            AlertDialog.Builder(this)
                .setTitle("${f.name} (${bmp.width}x${bmp.height})")
                .setView(iv)
                .setPositiveButton("Close", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showOpenWith(f: File) {
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setMessage("This file type is not supported for inline viewing.\n\nOpen with external app?")
            .setPositiveButton("Open") { _, _ ->
                try {
                    val ext = f.extension.lowercase()
                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", f
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Open with"))
                } catch (e: Exception) {
                    Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFileOptions(f: File) {
        val options = arrayOf("🗑️  Delete", "📋  Copy Path", "🔗  Share")
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmDelete(f)
                    1 -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("path", f.absolutePath))
                        Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show()
                    }
                    2 -> shareFile(f)
                }
            }
            .show()
    }

    private fun shareFile(f: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val ext = f.extension.lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val share = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(f: File) {
        val size = formatSize(f.length())
        val warning = if (f.isDirectory) "\n\n⚠️ This is a DIRECTORY. All contents will be deleted!" else ""
        AlertDialog.Builder(this)
            .setTitle("⚠️ Delete File?")
            .setMessage("Name: ${f.name}\nSize: $size\nPath: ${f.absolutePath}$warning")
            .setPositiveButton("Yes, Delete") { _, _ ->
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (ok) {
                    Toast.makeText(this, "Deleted: ${f.name}", Toast.LENGTH_SHORT).show()
                    loadFiles()
                } else {
                    Toast.makeText(this, "Delete failed", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Copy Path") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("path", f.absolutePath))
            }
            .show()
    }

    override fun onBackPressed() {
        if (currentDir.absolutePath != downloadsRoot.absolutePath && currentDir.parentFile != null && currentDir.parentFile.canRead()) {
            currentDir = currentDir.parentFile!!
            loadFiles()
        } else super.onBackPressed()
    }
}
