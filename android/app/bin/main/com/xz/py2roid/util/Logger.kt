package com.xz.py2roid.util

import android.util.Log

object Logger {
    private const val TAG = "py2roid"
    var enabled = true
    /** 调试覆盖层日志回调，由 MainViewModel 注册 */
    var onLog: ((String) -> Unit)? = null

    fun d(msg: String) {
        if (enabled) Log.d(TAG, msg)
    }
    fun i(msg: String) {
        if (enabled) { Log.i(TAG, msg); onLog?.invoke("[I] $msg") }
    }
    fun w(msg: String) {
        if (enabled) { Log.w(TAG, msg); onLog?.invoke("[W] $msg") }
    }
    fun e(msg: String, tr: Throwable? = null) {
        if (enabled) { Log.e(TAG, msg, tr); onLog?.invoke("[E] $msg") }
    }
}
