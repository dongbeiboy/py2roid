package com.xz.py2roid.util

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object Logger {
    private const val TAG = "py2roid"
    var enabled = true

    /** 日志事件流，任何订阅者都可收集，避免全局回调覆盖问题 */
    private val _logFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logFlow = _logFlow.asSharedFlow()

    fun d(msg: String) {
        if (enabled) Log.d(TAG, msg)
    }
    fun i(msg: String) {
        if (enabled) {
            Log.i(TAG, msg)
            _logFlow.tryEmit("[I] $msg")
        }
    }
    fun w(msg: String) {
        if (enabled) {
            Log.w(TAG, msg)
            _logFlow.tryEmit("[W] $msg")
        }
    }
    fun e(msg: String, tr: Throwable? = null) {
        if (enabled) {
            val enriched = if (tr != null) "$msg (${tr::class.simpleName})" else msg
            Log.e(TAG, enriched, tr)
            _logFlow.tryEmit("[E] $enriched")
        }
    }

    /**
     * 从日志行中提取来源类别标签，如 [Route]、[ADB]、[Load]、[SCRIPT] 等。
     * 格式1: [LEVEL] [Category] message  → 跳过级别前缀取第二个括号
     * 格式2: [Category] message（无级别前缀，如 [SCRIPT]）
     */
    fun extractLogCategory(line: String): String? {
        val m1 = Regex("^\\[[A-Z]\\] \\[([^\\]]+)\\]").find(line)
        if (m1 != null) return m1.groupValues[1]
        val m2 = Regex("^\\[([A-Z][A-Za-z0-9_]{2,})\\]").find(line)
        if (m2 != null) return m2.groupValues[1]
        return null
    }
}
