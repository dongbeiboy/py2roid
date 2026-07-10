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
}
