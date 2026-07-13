package com.xz.py2roid.vision

import android.content.Context
import com.xz.py2roid.util.Logger
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

        // 打印模型文件信息便于调试
        val modelFile = java.io.File(modelPath)
        Logger.i("[Route] backend=$backend model=${modelFile.name} size=${if (modelFile.exists()) modelFile.length() else -1}B")

        // VCAP 走独立引擎
        if (backend == InferenceBackend.VCAP) {
            Logger.d("[Route] trying VCAP engine ...")
            try {
                val vcap = VcapEngine(context)
                vcap.loadModel(modelPath)
                engine = vcap
                Logger.i("[Route] VCAP engine OK provider=${vcap.provider}")
                return
            } catch (e: Exception) {
                Logger.w("[Route] VCAP unavailable -> fallback ONNX")
            }
        }

        // TFLite 走独立引擎
        val isTfliteModel = modelPath.endsWith(".tflite", ignoreCase = true)
        if (backend == InferenceBackend.TFLITE || backend == InferenceBackend.TFLITE_GPU || backend == InferenceBackend.TFLITE_NNAPI ||
            (backend == InferenceBackend.Auto && isTfliteModel)) {
            Logger.d("[Route] trying TFLite engine backend=$backend ...")
            val tflite = TfliteEngine(context)
            tflite.loadModel(modelPath, backend)
            engine = tflite
            Logger.i("[Route] TFLite engine OK provider=${tflite.provider}")
            return
        }

        // ONNX Runtime 路径
        val isVcapResolved = backend == InferenceBackend.Auto &&
            DeviceProfile.getRecommendedBackend(context) == InferenceBackend.VCAP
        val onnxBackend = if (isVcapResolved) InferenceBackend.NNAPI else backend
        Logger.d("[Route] trying ONNX Runtime backend=$backend onnxBackend=$onnxBackend isVcapResolved=$isVcapResolved")
        val onnx = OnnxEngine(context)
        onnx.loadModel(modelPath, onnxBackend)
        engine = onnx
        Logger.i("[Route] ONNX Runtime OK provider=${onnx.provider}")
    }

    /** NMS 前最多保留的候选框数，防止高阈值场景 O(n²) 爆炸 */
    private var maxProposalsBeforeNms = 300

    @Synchronized
    fun detect(result: PreprocessResult): List<DetectionResult> {
        val inputTensor = result.tensor
        // NNAPI 垃圾输出自动回退到 CPU
        if (nnapiGarbageDetected && currentProvider == "NNAPI") {
            Logger.w("[Fallback] *** NNAPI garbage detected, reloading with CPU ***")
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
                Logger.w("[D08] Output too small ($totalElements), skipping frame")
                return emptyList()
            }

            // 动态推断输出结构：YOLOv8 输出为 (1, 4+numClasses, numProposals)
            // 标准 640x640 输入下 numProposals = 8400（80 类时 84*8400=705600，2 类时 6*8400=50400）
            val numProposals = if (totalElements % 8400 == 0) 8400 else totalElements / 85
            val numFeatures = totalElements / numProposals
            val numClasses = numFeatures - 4

            // 诊断日志
            diagFrameCount++
            val isDiagFrame = diagFrameCount % DIAG_INTERVAL == 0

            // 置信度采样（同时服务于 NNAPI 回退判断和诊断日志）
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
                Logger.d("[D07] Proposals=$numProposals features=$numFeatures classes=$numClasses output=$totalElements")
                val sampleStr = (0 until 10.coerceAtMost(numProposals)).joinToString { i ->
                    val cx = outputArray[i]
                    val cy = outputArray[1 * numProposals + i]
                    val w = outputArray[2 * numProposals + i]
                    val h = outputArray[3 * numProposals + i]
                    val p0 = outputArray[4 * numProposals + i]
                    // 采样最后一个类别（兼容少类别模型，如 last.onnx 只有 2 类）
                    val lastClassIdx = (numClasses - 1).coerceAtLeast(0)
                    val pLast = outputArray[(4 + lastClassIdx) * numProposals + i]
                    "(${"%.2f".format(cx)},${"%.2f".format(cy)}|${"%.0f".format(w)}x${"%.0f".format(h)}|p0=${"%.4f".format(p0)},pLast=${"%.4f".format(pLast)})"
                }
                Logger.d("[Sample] first 10: $sampleStr")
                Logger.d("[Dist] sampled=${numProposals / diagStep} maxConf=${"%.4f".format(diagMaxConf)} >0.1=$diagHighCount")
            }

            // NNAPI 垃圾输出检测
            if (diagMaxConf < 0.01f && currentProvider == "NNAPI" && !nnapiGarbageDetected) {
                Logger.w("[Fallback] *** NNAPI output all-zero (diagMaxConf=$diagMaxConf), scheduling CPU fallback ***")
                nnapiGarbageDetected = true
                onPerformanceUpdate(0f, "NNAPI(garbage)", System.currentTimeMillis() - startTime)
                return emptyList()
            }

            // ── Letterbox 坐标还原参数 ──
            val padL = result.padLeft.toFloat()
            val padT = result.padTop.toFloat()
            val s = result.scale
            val origW = result.origWidth.toFloat()
            val origH = result.origHeight.toFloat()

            // ── YOLOv8 解码 + letterbox 坐标还原 ──
            // 第一遍：收集所有 > 阈值的 detection，收集时直接转换坐标
            val rawDetections = mutableListOf<DetectionResult>()
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

                // 将 letterbox 空间坐标还原到原始图像空间
                val cxOrig = (cx - padL) / s
                val cyOrig = (cy - padT) / s
                val wOrig = w / s
                val hOrig = h / s

                // 归一化到 [0, 1]（相对于原始图像尺寸）
                val x1 = ((cxOrig - wOrig / 2f) / origW).coerceIn(0f, 1f)
                val y1 = ((cyOrig - hOrig / 2f) / origH).coerceIn(0f, 1f)
                val x2 = ((cxOrig + wOrig / 2f) / origW).coerceIn(0f, 1f)
                val y2 = ((cyOrig + hOrig / 2f) / origH).coerceIn(0f, 1f)

                // 优先使用模型元数据中的类别名（如 miku/teto），否则回退 COCO 或 class_N
                val label = when {
                    eng.classNames.containsKey(maxClassId) -> eng.classNames[maxClassId]!!
                    numClasses == 80 && maxClassId in COCO_CLASSES.indices -> COCO_CLASSES[maxClassId]
                    else -> "class_$maxClassId"
                }
                val frameAspect = origW / origH
                rawDetections.add(DetectionResult(label, maxClassId, maxProb, x1, y1, x2, y2, frameAspect = frameAspect))
            }

            // NMS 前按置信度取 top-K，减少 O(n²) 开销
            val candidates = if (rawDetections.size > maxProposalsBeforeNms) {
                rawDetections.sortedByDescending { it.confidence }.take(maxProposalsBeforeNms)
            } else {
                rawDetections
            }

            val finalDetections = NmsHelper.nms(candidates, iouThreshold)
            val elapsed = System.currentTimeMillis() - startTime
            val fps = if (elapsed > 0) 1000f / elapsed else 0f

            if (finalDetections.isNotEmpty()) {
                val classes = finalDetections.groupBy { it.label }.mapValues { it.value.size }
                Logger.i("[Result] final=${finalDetections.size} before_nms=${candidates.size} raw=${rawDetections.size} classes=$classes")
            }

            onDetectionResult(finalDetections)
            onPerformanceUpdate(fps, currentProvider, elapsed)
            return finalDetections
        } catch (e: Exception) {
            val eng = engine
            Logger.e("[D05] ${e::class.simpleName}: ${e.message} provider=$currentProvider engine=${eng?.name ?: "null"} input=${eng?.inputWidth}x${eng?.inputHeight}")
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
            Logger.w("Error closing engine")
        }
        engine = null
    }
}
