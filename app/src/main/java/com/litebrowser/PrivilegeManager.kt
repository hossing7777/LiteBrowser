package com.litebrowser

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object PrivilegeManager {
    private const val REQUEST_CODE = 1001

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Logger.e("PrivilegeManager", "requestPermission failed", e)
        }
    }

    fun executeShell(command: String): String {
        if (!isShizukuAvailable()) return "Shizuku not available"
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            Logger.e("PrivilegeManager", "executeShell failed: " + command, e)
            "Error: " + e.message
        }
    }

    fun grantAllPermissions(packageName: String) {
        val permissions = listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO"
        )
        for (perm in permissions) {
            executeShell("pm grant " + packageName + " " + perm)
        }
        executeShell("appops set --uid " + packageName + " MANAGE_EXTERNAL_STORAGE allow")
        executeShell("appops set " + packageName + " SYSTEM_ALERT_WINDOW allow")
        Logger.i("PrivilegeManager", "All permissions granted via Shizuku")
    }
}
