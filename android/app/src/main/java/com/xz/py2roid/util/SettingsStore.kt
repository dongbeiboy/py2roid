package com.xz.py2roid.util

import android.content.Context
import android.content.SharedPreferences
import com.xz.py2roid.ui.AppMode
import com.xz.py2roid.ui.AppSettings
import com.xz.py2roid.ui.CommMode
import com.xz.py2roid.ui.InferenceBackend

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("py2roid_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        return AppSettings(
            confidenceThreshold = prefs.getFloat("confidence", 0.5f),
            iouThreshold = prefs.getFloat("iou", 0.45f),
            commMode = CommMode.valueOf(prefs.getString("commMode", CommMode.USB.name) ?: CommMode.USB.name),
            inferenceBackend = InferenceBackend.valueOf(prefs.getString("backend", InferenceBackend.Auto.name) ?: InferenceBackend.Auto.name),
            debugOverlayEnabled = prefs.getBoolean("debugOverlay", false),
            debugOverlayLevelFilter = prefs.getString("debugOverlayFilter", "E,W,I")
                ?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: setOf("E", "W", "I"),
            debugOverlayHiddenCategories = prefs.getString("debugOverlayHiddenCats", "")
                ?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
            startOnConfig = prefs.getBoolean("startOnConfig", true),
            appMode = AppMode.valueOf(prefs.getString("appMode", AppMode.LEGACY.name) ?: AppMode.LEGACY.name)
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit().apply {
            putFloat("confidence", settings.confidenceThreshold)
            putFloat("iou", settings.iouThreshold)
            putString("commMode", settings.commMode.name)
            putString("backend", settings.inferenceBackend.name)
            putBoolean("debugOverlay", settings.debugOverlayEnabled)
            putString("debugOverlayFilter", settings.debugOverlayLevelFilter.joinToString(","))
            putString("debugOverlayHiddenCats", settings.debugOverlayHiddenCategories.joinToString(","))
            putBoolean("startOnConfig", settings.startOnConfig)
            putString("appMode", settings.appMode.name)
            apply()
        }
    }

    fun getSelectedModel(): String = prefs.getString("selectedModel", "last.onnx") ?: "last.onnx"
    fun setSelectedModel(name: String) { prefs.edit().putString("selectedModel", name).apply() }

    fun isFirstLaunch(): Boolean = prefs.getBoolean("first_launch", true)
    fun setFirstLaunchDone() { prefs.edit().putBoolean("first_launch", false).apply() }
}
