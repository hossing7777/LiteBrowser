package com.litebrowser

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private const val TAG = "LiteBrowser"
    private val logFile: File by lazy {
        File("/storage/emulated/0/Download/LiteBrowser.log")
    }
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun i(tag: String, msg: String) {
        Log.i("$TAG/$tag", msg)
        write("I", tag, msg)
    }

    @Synchronized
    fun d(tag: String, msg: String) {
        Log.d("$TAG/$tag", msg)
        write("D", tag, msg)
    }

    @Synchronized
    fun w(tag: String, msg: String) {
        Log.w("$TAG/$tag", msg)
        write("W", tag, msg)
    }

    @Synchronized
    fun e(tag: String, msg: String, e: Throwable? = null) {
        Log.e("$TAG/$tag", msg, e)
        val full = if (e != null) "$msg\n${stackTraceOf(e)}" else msg
        write("E", tag, full)
    }

    @Synchronized
    fun crash(thread: Thread, ex: Throwable) {
        write("CRASH", thread.name, stackTraceOf(ex))
        Log.e(TAG, "CRASH on ${thread.name}", ex)
    }

    private fun stackTraceOf(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun write(level: String, tag: String, msg: String) {
        try {
            val time = timeFmt.format(Date())
            // هر خط را جداگانه با indentation مینویسیم
            val lines = msg.split("\n")
            val sb = StringBuilder()
            for (line in lines) {
                sb.append(time).append(" [").append(level).append("] [").append(tag).append("] ").append(line).append("\n")
            }
            logFile.appendText(sb.toString())
        } catch (ex: Exception) {
            // اگر نوشتن فایل شکست خورد (مثلاً دسترسی نیست) حداقل در logcat ثبت شود
            Log.e(TAG, "Cannot write log file: ${ex.message}")
        }
    }

    /** پاک کردن فایل لاگ — برای شروع تمیز در هر اجرا */
    fun reset() {
        try {
            logFile.writeText("")
            write("I", "Logger", "=== Log file reset at app start ===")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot reset log file: ${e.message}")
        }
    }
}
