package com.adblive.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

object SecureSettingsPermission {
    fun isGranted(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun ensureGranted(context: Context, rootAvailable: Boolean): Boolean {
        if (isGranted(context)) return true
        if (!rootAvailable) return false
        val result = ShellUtils.executeSu(
            "pm grant com.adblive.app android.permission.WRITE_SECURE_SETTINGS"
        )
        return result.isSuccess() && isGranted(context)
    }
}
