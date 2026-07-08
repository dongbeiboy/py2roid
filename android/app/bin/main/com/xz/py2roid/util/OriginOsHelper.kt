package com.xz.py2roid.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object OriginOsHelper {

    fun isOriginOs(): Boolean {
        return Build.MANUFACTURER.equals("vivo", ignoreCase = true) ||
               Build.BRAND.equals("vivo", ignoreCase = true) ||
               Build.FINGERPRINT.contains("vivo", ignoreCase = true)
    }

    /** 跳转电池优化白名单设置 */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // fallback: 应用详情页
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.parse("package:${context.packageName}")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    /** 跳转自启动管理页（vivo 专属，可能需要手动） */
    fun openAutoStartSettings(context: Context) {
        try {
            val intent = Intent()
            intent.setClassName("com.vivo.abe", "com.vivo.abe.autostart.AutoStartActivity")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            openBatteryOptimizationSettings(context)
        }
    }
}
