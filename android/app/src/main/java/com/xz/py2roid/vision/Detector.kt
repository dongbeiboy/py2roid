package com.xz.py2roid.vision

import android.content.Context
import android.util.Log
import com.xz.py2roid.ui.InferenceBackend

class Detector(
    private val context: Context,
    private val modelManager: ModelManager,
    private val imagePreprocessor: ImagePreprocessor,
    private val onDetectionResult: (List<DetectionResult>) -> Unit,
    private val onPerformanceUpdate: (fps: Float, provider: String, frameTimeMs: Long) -> Unit
) {

    companion object {
        private const val TAG = "py2roid.Detector"
        private const val DIAG_INTERVAL = 30

        val COCO_CLASSES = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )
    }

    @Volatile
    private var engine: InferenceEngine? = null

    @Volatile
    var confidenceThreshold: Float = 0.5f

    @Volatile
    var iouThreshold: Float = 0.45f

    val currentProvider: String get() = engine?.provider ?: "NONE"

    // NNAPI 垃圾输出自动回退
    @Volatile
    private var nnapiGarbageDetected = false
    private var currentModelPath: String? = null
    private var currentBackend: InferenceBackend = InferenceBackend.Auto

    private var diagFrameCount = 0

    /**
     * 加载模型并指定后端。
     * VCAP 后端使用 VcapEngine，其余使用 OnnxEngine。
     * VcapEngine 不可用时自动回退 OnnxEngine。
     */
    @Synchronized
    fun loadModel(modelPath: String, backend: InferenceBackend) {
        closeEngine()
        currentModelPath = modelPath
        currentBackend = backend
        nnapiGarbageDetected = false

        // VCAP 走独立引擎
        if (backend == InferenceBackend.VCAP) {
            try {
                val vcap = VcapEngine(context)
                vcap.loadModel(modelPath)
                engine = vcap
                Log.i(TAG, "VCAP engine loaded: $modelPath provider=${vcap.provider}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "VCAP unavailable, falling back to ONNX Runtime", e)
            }
        }

        // ONNX Runtime 路径（含 Auto/CPU/XNNPACK/NNAPI）
        val onnx = OnnxEngine(context)
        val onnxBackend = if (backend == InferenceBackend.VCAP) InferenceBackend.Auto else backend
        onnx.loadModel(modelPath, onnxBackend)
        engine = onnx
    }

    @Synchronized
    fun detect(inputTensor: FloatArray, inputWidth: Int, inputHeight: Int): List<DetectionResult> {
        // NNAPI 垃圾输出自动回退到 CPU
        if (nnapiGarbageDetected && currentProvider == "NNAPI") {
            Log.w(TAG, "[Fallback] NNAPI garbage detected, reloading with CPU")
            currentModelPath?.let { loadModel(it, InferenceBackend.CPU) }
            nnapiGarbageDetected = false
        }

        val eng = engine ?: throw IllegalStateException("Model not loaded")
        val startTime = System.currentTimeMillis()

        try {
            val outputArray = eng.infer(inputTensor)

            // 输出解析
            val totalElements = outputArray.size
            if (totalElements < 84) {
                Log.w(TAG, "[D08] Output too small ($totalElements), skipping frame")
                return emptyList()
            }

            val numProposals = if (totalElements == 705600) 8400 else totalElements / 85
            val numFeatures = totalElements / numProposals
            val numClasses = numFeatures - 4

            // 诊断日志
            diagFrameCount++
            val isDiagFrame = diagFrameCount % DIAG_INTERVAL == 0
            if (isDiagFrame) {
                Log.d(TAG, "[D07] Proposals=$numProposals features=$numFeatures classes=$numClasses output=$totalElements")
                val sampleStr = (0 until 10.coerceAtMost(numProposals)).joinToString { i ->
                    val cx = outputArray[i]
                    val cy = outputArray[1 * numProposals + i]
                    val w = outputArray[2 * numProposals + i]
                    val h = outputArray[3 * numProposals + i]
                    val p0 = outputArray[4 * numProposals + i]
                    val p10 = outputArray[(4 + 10) * numProposals + i]
                    "(${"%.2f".format(cx)},${"%.2f".format(cy)}|${"%.0f".format(w)}x${"%.0f".format(h)}|p0=${"%.4f".format(p0)},p10=${"%.4f".format(p10)})"
                }
                Log.d(TAG, "[Sample] first 10: $sampleStr")
            }

            // 置信度统计（用于 NNAPI 回退判断）
            val diagStep = (numProposals / 100).coerceAtLeast(1)
            var diagMaxConf = 0f
            var diagHighCount = 0
            for (s in 0 until numProposals step diagStep) {
                var best = 0f
                for (c in 0 until numClasses.coerceAtMost(80)) {
                    val p = outputArray[(4 + c) * numProposals + s]
                    if (p > best) best = p
                }
                if (best > diagMaxConf) diagMaxConf = best
                if (best > 0.1f) diagHighCount++
            }
            if (isDiagFrame) {
                Log.d(TAG, "[Dist] sampled=${numProposals / diagStep} maxConf=${"%.4f".format(diagMaxConf)} >0.1=$diagHighCount")
            }

            // NNAPI 垃圾输出检测
            if (diagMaxConf < 0.01f && currentProvider == "NNAPI" && !nnapiGarbageDetected) {
                Log.w(TAG, "[Fallback] NNAPI output all-zero, scheduling CPU fallback")
                nnapiGarbageDetected = true
                onPerformanceUpdate(0f, "NNAPI(garbage)", System.currentTimeMillis() - startTime)
                return emptyList()
            }

            // YOLOv8 解码
            val detections = mutableListOf<DetectionResult>()
            for (i in 0 until numProposals) {
                val cx = outputArray[i]
                val cy = outputArray[1 * numProposals + i]
                val w = outputArray[2 * numProposals + i]
                val h = outputArray[3 * numProposals + i]

                var maxProb = 0f
                var maxClassId = 0
                for (c in 0 until numClasses.coerceAtMost(80)) {
                    val prob = outputArray[(4 + c) * numProposals + i]
                    if (prob > maxProb) {
                        maxProb = prob
                        maxClassId = c
                    }
                }
                if (maxProb < confidenceThreshold) continue

                val x1 = ((cx - w / 2f) / inputWidth).coerceIn(0f, 1f)
                val y1 = ((cy - h / 2f) / inputHeight).coerceIn(0f, 1f)
                val x2 = ((cx + w / 2f) / inputWidth).coerceIn(0f, 1f)
                val y2 = ((cy + h / 2f) / inputHeight).coerceIn(0f, 1f)

                val label = if (maxClassId in COCO_CLASSES.indices) COCO_CLASSES[maxClassId] else "class_$maxClassId"
                detections.add(DetectionResult(label, maxClassId, maxProb, x1, y1, x2, y2))
            }

            val finalDetections = NmsHelper.nms(detections, iouThreshold)
            val elapsed = System.currentTimeMillis() - startTime
            val fps = if (elapsed > 0) 1000f / elapsed else 0f

            if (finalDetections.isNotEmpty()) {
                val classes = finalDetections.groupBy { it.label }.mapValues { it.value.size }
                Log.i(TAG, "[Result] final=${finalDetections.size} before_nms=${detections.size} classes=$classes")
            }

            onDetectionResult(finalDetections)
            onPerformanceUpdate(fps, currentProvider, elapsed)
            return finalDetections
        } catch (e: Exception) {
            Log.e(TAG, "[D05] Detection failed: ${e.message}", e)
            onPerformanceUpdate(0f, currentProvider, System.currentTimeMillis() - startTime)
            throw e
        }
    }

    @Synchronized
    fun close() {
        closeEngine()
        imagePreprocessor.close()
    }

    private fun closeEngine() {
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing engine", e)
        }
        engine = null
    }
}
