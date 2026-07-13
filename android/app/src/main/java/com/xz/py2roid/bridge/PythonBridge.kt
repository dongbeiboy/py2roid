package com.xz.py2roid.bridge

import android.util.Log
import com.chaquo.python.Python
import com.xz.py2roid.vision.DetectionResult
import java.nio.ByteBuffer

/**
 * Kotlin ↔ Python 轻量消息路由层。
 *
 * 职责约束：
 * 1. 帧缓存（getFrame/updateFrame）
 * 2. 回调注册（registerCallback 向 Python 暴露 Kotlin 能力）
 * 3. 请求分发（sendToUSB/sendToWS/uartWrite 等）
 *
 * 禁止直接编写业务逻辑——三行以上的"桥接+处理"应拆到独立类。
 */
object PythonBridge {
    private const val TAG = "py2roid.PythonBridge"

    // ── 帧缓存 ──
    // Kotlin 写入最新的 NV21/BGR 帧，Python 侧 sensor.snapshot() 读出

    @Volatile private var cachedFrame: FrameData? = null

    data class FrameData(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val format: Int,    // 1=RGB565, 2=GRAYSCALE, 3=NV21
        val rotation: Int   // 0/90/180/270
    )

    /**
     * 更新帧缓存（由 CameraController 每帧调用）。
     */
    fun updateFrame(data: ByteArray, width: Int, height: Int, format: Int, rotation: Int) {
        cachedFrame = FrameData(data, width, height, format, rotation)
    }

    /**
     * 获取最新帧（由 Python sensor.snapshot() 调用）。
     * 返回原始缓存的帧数据 + 格式信息。
     * 格式转换将在 Phase 2 中的 OpenMV sensor 模块完成。
     */
    fun getFrameBytes(): FrameData? {
        return cachedFrame
    }

    // ── Python 初始化 ──

    fun init() {
        try {
            Python.getInstance().getModule("main").callAttr("init")
            Log.i(TAG, "Python init OK")
        } catch (e: Exception) {
            Log.e(TAG, "[P01] Python init failed", e)
        }
    }

    // ── 协议编码 ──

    fun encodeDetection(
        targets: List<DetectionResult>,
        imgWidth: Int = 0,
        imgHeight: Int = 0
    ): ByteArray? {
        return try {
            val targetList = targets.map { t ->
                listOf(t.classId, t.confidence, t.x1, t.y1, t.x2, t.y2)
            }
            val result = Python.getInstance().getModule("main")
                .callAttr("encode_detection", targetList, imgWidth, imgHeight)
            result.toJava(ByteArray::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "[P02] encode_detection failed", e)
            null
        }
    }

    // ── 数据接收 ──

    fun onDataReceived(data: ByteArray) {
        try {
            Python.getInstance().getModule("main")
                .callAttr("on_data_received", data)
        } catch (e: Exception) {
            Log.e(TAG, "on_data_received failed", e)
        }
    }

    // ── 回调注册 ──

    fun registerCallback(name: String, callback: Any) {
        try {
            Python.getInstance().getModule("main")
                .callAttr("register_callback", name, callback)
        } catch (e: Exception) {
            Log.e(TAG, "register_callback failed: $name", e)
        }
    }
}
