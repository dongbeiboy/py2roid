package com.xz.py2roid.util

import android.content.Context
import com.xz.py2roid.vision.DeviceProfile
import java.io.File

/**
 * 系统状态采集器。
 *
 * CPU 占用率通过多路 fallback 获取：
 *   1. /proc/stat 直接读取（File – 部分平台不可用）
 *   2. /proc/stat 通过 shell 读取（Runtime.exec – 兼容 Android 16）
 *   3. /proc/loadavg
 *   4. 进程级 CPU 时间（Process.getElapsedCpuTime）
 * 温度通过 sysfs thermal zone 读取；
 * GPU 占用率在部分平台（Qualcomm kgsl）可读。
 */
object SystemStats {
    private const val TAG = "py2roid.SystemStats"

    // ── CPU 使用率 ──────────────────────────────────────────

    /**
     * CPU 使用率 0-100。
     *
     * 通过 android.os.Process.getElapsedCpuTime() 计算本进程的 CPU 占用率，
     * 无需读取 /proc 或调用 shell 命令，兼容 Android 16 限制。
     */
    fun cpuLoad(): Int {
        val wallNow = System.currentTimeMillis()
        val cpuNow = android.os.Process.getElapsedCpuTime()

        if (_prevWallTime <= 0 || _prevCpuTime <= 0) {
            _prevWallTime = wallNow
            _prevCpuTime = cpuNow
            return 0
        }

        val wallDelta = wallNow - _prevWallTime
        val cpuDelta = cpuNow - _prevCpuTime

        _prevWallTime = wallNow
        _prevCpuTime = cpuNow

        if (wallDelta <= 0 || cpuDelta <= 0) return 0

        // cpuDelta 是 ms，wallDelta 也是 ms，相除 ≈ 进程占用核心百分比
        // 再除以核心数得到单核百分比
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val pct = (cpuDelta.toFloat() / wallDelta.toFloat() / cores * 100).toInt().coerceIn(0, 100)
        return pct
    }

    // ── 温度 ────────────────────────────────────────────────

    /** CPU 最高温度 ℃（通过 sysfs thermal zone 读取） */
    fun cpuTemp(context: Context): Int {
        return readThermalZone(context, DeviceProfile.getSystemConfig(context).cpuTempZones, isGpu = false)
    }

    /** GPU 温度 ℃ */
    fun gpuTemp(context: Context): Int {
        return readThermalZone(context, DeviceProfile.getSystemConfig(context).gpuTempZones, isGpu = true)
    }

    /**
     * 通过 sysfs 读取 thermal zone 温度。
     * 基础路径由 DeviceProfile 配置（兼容 Android 16 路径变化）。
     */
    private fun readThermalZone(context: Context, zoneNames: List<String>, isGpu: Boolean): Int {
        val cfg = DeviceProfile.getSystemConfig(context)
        val base = cfg.thermalBase.trimEnd('/')
        val dir = File(base)
        val zones = try {
            dir.listFiles { f -> f.name.startsWith("thermal_zone") }?.toList() ?: emptyList()
        } catch (e: Exception) {
            Logger.d("[SysStats] Cannot list $base: ${e.message}")
            return 0
        }

        if (zones.isEmpty()) {
            Logger.d("[SysStats] No thermal zones at $base (no access?)")
        }

        // 按名称精确匹配
        if (zoneNames.isNotEmpty()) {
            for (zone in zones) {
                val type = try { File(zone, "type").readText().trim() } catch (_: Exception) { continue }
                if (type in zoneNames) {
                    val t = readZoneTemp(zone)
                    if (t > 0) return t
                }
            }
        }

        // fallback：关键词匹配
        for (zone in zones) {
            val type = try { File(zone, "type").readText().trim() } catch (_: Exception) { continue }
            val keywords = if (isGpu) listOf("gpu") else listOf("cpu", "soc", "tsens")
            if (keywords.any { type.contains(it, ignoreCase = true) }) {
                val t = readZoneTemp(zone)
                if (t > 0) return t
            }
        }

        // 兜底：任意合法温度
        for (zone in zones) {
            val t = readZoneTemp(zone)
            if (t > 0) return t
        }
        return 0
    }

    private fun readZoneTemp(zoneDir: File): Int {
        return try {
            val raw = File(zoneDir, "temp").readText().trim().toInt()
            val t = raw / 1000
            if (t in 10..150) t else 0
        } catch (_: Exception) { 0 }
    }

    // ── GPU 占用率 ─────────────────────────────────────────

    /**
     * GPU 占用率 0-100。
     * -1 表示当前平台不支持（HUD 显示为 N/A）。
     */
    fun gpuLoad(context: Context): Int {
        val cfg = DeviceProfile.getSystemConfig(context)
        if (!cfg.gpuUtilAvailable) return -1

        val kgsl = try {
            File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").readText().trim()
                .replace("%", "").trim().toIntOrNull()
        } catch (_: Exception) { null }
        if (kgsl != null) return kgsl.coerceIn(0, 100)

        val mali = try {
            File("/proc/mali/utilization").readText().trim().toIntOrNull()
        } catch (_: Exception) { null }
        if (mali != null) return mali.coerceIn(0, 100)

        return 0
    }

    // ── 兼容方法 ────────────────────────────────────────────
    fun cpuTemp(): Int = 0
    fun gpuLoad(): Int = 0

    private var _prevWallTime = 0L
    private var _prevCpuTime = 0L
}
