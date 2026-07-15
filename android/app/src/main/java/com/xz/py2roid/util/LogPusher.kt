package com.xz.py2roid.util

import android.util.Log
import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 日志推送器 —— 订阅 Logger.logFlow，批量转发日志到 web。
 *
 * 策略：满10条 或 满10秒，先到先发。
 *
 * 用法（ADB）:
 *   adb shell am start -n com.xz.py2roid/.MainActivity \
 *     --es log_web http://192.168.1.100:8765
 */
object LogPusher {

    private const val TAG = "py2roid.LogPusher"
    private const val BATCH_SIZE = 10
    private const val FLUSH_INTERVAL_MS = 10_000L

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
            val lock = Any()

            // 收集协程：满 BATCH_SIZE 条即刷
            val collectJob = launch {
                Logger.logFlow.collect { line ->
                    val shouldFlush: Boolean
                    synchronized(lock) {
                        batch.add(line)
                        shouldFlush = batch.size >= BATCH_SIZE
                    }
                    if (shouldFlush) flushBatch(safeUrl, batch, lock)
                }
            }

            // 定时协程：每 FLUSH_INTERVAL_MS 刷一次
            val timerJob = launch {
                while (isActive) {
                    delay(FLUSH_INTERVAL_MS)
                    val shouldFlush: Boolean
                    synchronized(lock) {
                        shouldFlush = batch.isNotEmpty()
                    }
                    if (shouldFlush) flushBatch(safeUrl, batch, lock)
                }
            }

            // 等待收集协程结束（flow 不结束则一直跑）
            collectJob.join()
            timerJob.cancel()
            // 退出前刷剩余
            synchronized(lock) {
                if (batch.isNotEmpty()) {
                    val leftover = batch.toList()
                    batch.clear()
                    launch { flushBatchInternal(safeUrl, leftover) }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 原子交换 batch，避免并发刷出重复数据 */
    private suspend fun flushBatch(baseUrl: String, batch: MutableList<String>, lock: Any) {
        val lines: List<String>
        synchronized(lock) {
            if (batch.isEmpty()) return
            lines = batch.toList()
            batch.clear()
        }
        flushBatchInternal(baseUrl, lines)
    }

    private suspend fun flushBatchInternal(baseUrl: String, lines: List<String>) {
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
