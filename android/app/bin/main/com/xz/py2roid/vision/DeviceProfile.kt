package com.xz.py2roid.vision

import android.content.Context
import android.os.Build
import android.util.Log
import com.xz.py2roid.ui.InferenceBackend
import org.json.JSONArray
import org.json.JSONObject

/**
 * 推理后端 + 系统监控自动调度 —— 根据设备厂商/型号匹配最优配置。
 */
object DeviceProfile {
    private const val TAG = "py2roid.DeviceProfile"
    private const val PROFILE_FILE = "device_profiles.json"

    private data class Rule(
        val manufacturer: String?,
        val model: String?,
        val backend: InferenceBackend,
        val system: SystemConfig?
    )

    /** 系统监控配置 */
    data class SystemConfig(
        /** thermal sysfs 基础路径，Android 16 后为 /sys/devices/virtual/thermal */
        val thermalBase: String = "/sys/class/thermal",
        /** 用作 CPU 温度的 thermal zone 名称列表 */
        val cpuTempZones: List<String> = emptyList(),
        /** 用作 GPU 温度的 thermal zone 名称列表 */
        val gpuTempZones: List<String> = emptyList(),
        /** GPU 占用率是否可读（仅部分平台支持） */
        val gpuUtilAvailable: Boolean = false,
        /** 物理 CPU 核心数 */
        val numCpuCores: Int = 0
    )

    private var rules: List<Rule> = emptyList()
    private var defaultBackend: InferenceBackend = InferenceBackend.NNAPI
    private var defaultSystem: SystemConfig = SystemConfig()
    private var loaded = false

    /** 当前匹配到的 system 配置（懒加载后缓存） */
    private var _currentSystem: SystemConfig? = null
    private var _currentBackend: InferenceBackend? = null

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
                    model = match.optString("model", "").ifEmpty { null },
                    backend = InferenceBackend.valueOf(rule.getString("backend")),
                    system = jsonObjToSystemConfig(rule.optJSONObject("system"))
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

    private fun jsonObjToSystemConfig(obj: JSONObject?): SystemConfig? {
        if (obj == null) return null
        return SystemConfig(
            thermalBase = obj.optString("thermal_base", "/sys/class/thermal"),
            cpuTempZones = obj.optJSONArray("cpu_temp_zones")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            gpuTempZones = obj.optJSONArray("gpu_temp_zones")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            gpuUtilAvailable = obj.optBoolean("gpu_util_available", false),
            numCpuCores = obj.optInt("num_cpu_cores", 0)
        )
    }

    /** 匹配当前设备，返回对应的 Rule */
    private fun matchRule(context: Context): Rule? {
        load(context)
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()

        // 先精确匹配 manufacturer + model
        for (rule in rules) {
            val m = rule.manufacturer?.lowercase()
            val mo = rule.model?.lowercase()
            if (m != null && mo != null && manufacturer == m && model == mo) {
                Log.d(TAG, "Exact match: $manufacturer $model → ${rule.backend}")
                return rule
            }
        }

        // 再模糊匹配 manufacturer
        for (rule in rules) {
            val m = rule.manufacturer?.lowercase()
            if (m != null && rule.model == null && manufacturer.contains(m)) {
                Log.d(TAG, "Manufacturer match: $manufacturer → ${rule.backend}")
                return rule
            }
        }

        Log.d(TAG, "No rule matched manuf=$manufacturer model=$model")
        return null
    }

    /** 获取当前设备推荐后端 */
    fun getRecommendedBackend(context: Context): InferenceBackend {
        if (_currentBackend != null) return _currentBackend!!
        val rule = matchRule(context)
        _currentBackend = rule?.backend ?: defaultBackend
        return _currentBackend!!
    }

    /** 获取当前设备的系统监控配置（含 thermal base path、GPU 可用性等） */
    fun getSystemConfig(context: Context): SystemConfig {
        if (_currentSystem != null) return _currentSystem!!
        val rule = matchRule(context)
        _currentSystem = rule?.system ?: defaultSystem
        // 补齐默认值
        if (_currentSystem!!.cpuTempZones.isEmpty()) {
            _currentSystem = _currentSystem!!.copy(cpuTempZones = listOf("cpu", "soc"))
        }
        Log.d(TAG, "System config: ${_currentSystem}")
        return _currentSystem!!
    }

    /** 重置缓存（配置文件变更时调用） */
    fun resetCache() {
        loaded = false
        _currentSystem = null
        _currentBackend = null
    }
}
