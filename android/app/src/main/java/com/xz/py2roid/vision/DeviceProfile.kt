package com.xz.py2roid.vision

import android.content.Context
import android.os.Build
import android.util.Log
import com.xz.py2roid.ui.InferenceBackend
import org.json.JSONArray
import org.json.JSONObject

/**
 * 推理后端自动调度 —— 根据设备厂商/型号匹配最优后端。
 */
object DeviceProfile {
    private const val TAG = "py2roid.DeviceProfile"
    private const val PROFILE_FILE = "device_profiles.json"

    private data class Rule(
        val manufacturer: String?,
        val backend: InferenceBackend
    )

    private var rules: List<Rule> = emptyList()
    private var defaultBackend: InferenceBackend = InferenceBackend.NNAPI
    private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        try {
            val json = context.assets.open(PROFILE_FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val arr = root.getJSONArray("rules")
            rules = (0 until arr.length()).map { i ->
                val rule = arr.getJSONObject(i)
                val match = rule.getJSONObject("match")
                Rule(
                    manufacturer = match.optString("manufacturer", "").ifEmpty { null },
                    backend = InferenceBackend.valueOf(rule.getString("backend"))
                )
            }
            defaultBackend = InferenceBackend.valueOf(root.getString("default"))
            loaded = true
            Log.d(TAG, "Loaded ${rules.size} rules, default=$defaultBackend")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load device profile", e)
            defaultBackend = InferenceBackend.NNAPI
        }
    }

    /** 获取当前设备推荐后端 */
    fun getRecommendedBackend(context: Context): InferenceBackend {
        load(context)
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()

        for (rule in rules) {
            if (rule.manufacturer != null && manufacturer.contains(rule.manufacturer.lowercase())) {
                Log.d(TAG, "Matched manufacturer=$manufacturer → ${rule.backend}")
                return rule.backend
            }
        }

        Log.d(TAG, "No rule matched manuf=$manufacturer model=$model, using default=$defaultBackend")
        return defaultBackend
    }
}
