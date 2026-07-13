package com.xz.py2roid.util

import android.util.Log
import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 日志推送器 —— 订阅 Logger.logFlow，转发日志覆盖层消息到 web。
 *
 * 用法（ADB）:
 *   adb shell am start -n com.xz.py2roid/.MainActivity \
 *     --es log_web http://192.168.1.100:8765
 */
object LogPusher {

    private const val TAG = "py2roid.LogPusher"
    private var job: Job? = null

    val isActive: Boolean get() = job?.isActive == true

    fun start(targetUrl: String, scope: CoroutineScope, debuggable: Boolean = false) {
        if (!debuggable) {
            Log.w(TAG, "Refused: not a debug build")
            return
        }
        stop()

        val safeUrl = targetUrl.trimEnd('/').let { url ->
            if (!url.startsWith("http://") && !url.startsWith("https://"))
                "http://$url" else url
        }
        Log.i(TAG, "Starting -> $safeUrl")

        job = scope.launch(Dispatchers.IO) {
            // 注册
            try {
                val conn = URL("$safeUrl/register?source=py2roid").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}

            val batch = mutableListOf<String>()

            // 直接订阅 Logger.logFlow —— 和日志覆盖层同源
            Logger.logFlow.collect { line ->
                batch.add(line)
                if (batch.size >= 10) {
                    flushBatch(safeUrl, batch)
                    batch.clear()
                }
            }
            // 退出前刷剩余
            if (batch.isNotEmpty()) flushBatch(safeUrl, batch)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun flushBatch(baseUrl: String, lines: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                val body = lines.joinToString("\n")
                val encoded = URLEncoder.encode(body, "UTF-8")
                val conn = URL("$baseUrl/log").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write("lines=$encoded") }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }
}
