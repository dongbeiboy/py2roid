package com.xz.py2roid.bridge

import android.util.Log
import com.chaquo.python.Python
import com.xz.py2roid.vision.DetectionResult

object PythonBridge {
    private const val TAG = "py2roid.PythonBridge"

    fun init() {
        try {
            Python.getInstance().getModule("main").callAttr("init")
            Log.i(TAG, "Python init OK")
        } catch (e: Exception) {
            Log.e(TAG, "[P01] Python init failed", e)
        }
    }

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

    fun onDataReceived(data: ByteArray) {
        try {
            Python.getInstance().getModule("main")
                .callAttr("on_data_received", data)
        } catch (e: Exception) {
            Log.e(TAG, "on_data_received failed", e)
        }
    }

    fun registerCallback(name: String, callback: Any) {
        try {
            Python.getInstance().getModule("main")
                .callAttr("register_callback", name, callback)
        } catch (e: Exception) {
            Log.e(TAG, "register_callback failed: $name", e)
        }
    }
}
