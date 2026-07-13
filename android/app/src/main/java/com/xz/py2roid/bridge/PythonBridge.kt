package com.xz.py2roid.bridge

import android.util.Log
import com.chaquo.python.Python
import com.xz.py2roid.serial.UsbSerialManager
import com.xz.py2roid.vision.DetectionResult
import com.xz.py2roid.vision.Detector
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking

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

    @Volatile private var cachedFrame: FrameData? = null
    private var frameSeq = 0L

    /** USB 响应队列（Python bridge 轮询读取）。上限 1024 防 OOM。 */
    private val usbResponseQueue = ConcurrentLinkedQueue<ByteArray>()
    private const val USB_RESPONSE_QUEUE_MAX = 1024

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
        frameSeq++
    }

    /**
     * JNI 调用入口：获取最新帧，仅在序列号变化时返回新数据。
     *
     * 返回编码格式：
     *   [NV21_data] + [height(2)] + [width(2)] + [fmt(1)] + [rotation(1)]
     *
     * @param prevSeq 上次获取的 seq
     * @return Pair<ByteArray, Long>? — 新帧数据+新 seq，或 null（无新帧）
     */
    @JvmStatic
    fun getFrame(prevSeq: Long): Array<Any?>? {
        val frame = cachedFrame ?: return null
        if (prevSeq == frameSeq) return null

        // 编码：data + height(LE2) + width(LE2) + format + rotation
        return try {
            val meta = ByteArray(6)
            meta[0] = (frame.height and 0xFF).toByte()
            meta[1] = ((frame.height shr 8) and 0xFF).toByte()
            meta[2] = (frame.width and 0xFF).toByte()
            meta[3] = ((frame.width shr 8) and 0xFF).toByte()
            meta[4] = frame.format.toByte()
            meta[5] = frame.rotation.toByte()
            val payload = frame.data + meta
            arrayOf(payload, frameSeq)
        } catch (e: Exception) {
            Log.e(TAG, "getFrame failed", e)
            null
        }
    }

    /**
     * USB 发送（由 Python jclass 调用）。
     */
    @JvmStatic
    fun sendToUsb(data: ByteArray) {
        try {
            kotlinx.coroutines.runBlocking {
                UsbSerialManager.write(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendToUsb failed", e)
        }
    }

    /**
     * USB 响应读取（非阻塞，由 Python jclass 调用）。
     */
    @JvmStatic
    fun readUsbResponse(size: Int): ByteArray? {
        return usbResponseQueue.poll()
    }

    /**
     * 向 USB 响应队列推送数据（由 onDataReceived 调用）。
     * 队列超上限时丢弃最旧数据防 OOM。
     */
    @JvmStatic
    fun pushUsbResponse(data: ByteArray) {
        usbResponseQueue.offer(data)
        // 上限保护：队列超过 USB_RESPONSE_QUEUE_MAX 时裁剪
        while (usbResponseQueue.size > USB_RESPONSE_QUEUE_MAX) {
            usbResponseQueue.poll() ?: break
        }
    }

    /**
     * ML 推理（由 Python ml.predict 调用）。
     * 当前为空实现——Kotlin 侧的 YOLO 推理管线独立运行，不经过 Python。
     * OpenMV 兼容模式下如需 Python 侧调用推理，需后续接入 Detector 代理。
     */
    @JvmStatic
    fun mlPredict(inputData: ByteArray, modelName: String, backend: String): String? {
        Log.w(TAG, "mlPredict called but not implemented (model=$modelName backend=$backend)")
        return null
    }

    /** Python 初始化完成 deferred（由 init() 完成后标记，startDetection 等待）。 */
    val initDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** 挂起版本：等待 Python init 完成（在 IO 线程调用）。 */
    suspend fun init() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            Python.getInstance().getModule("main").callAttr("init")
            Log.i(TAG, "Python init OK")
            initDeferred.complete(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "[P01] Python init failed", e)
            initDeferred.completeExceptionally(e)
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
            // 推送到 USB 响应队列供 Python bridge 轮询
            usbResponseQueue.offer(data)
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
