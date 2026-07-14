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
        if (isTfliteModel && (backend == InferenceBackend.TFLITE || backend == InferenceBackend.TFLITE_GPU ||
                backend == InferenceBackend.TFLITE_NNAPI || backend == InferenceBackend.Auto ||
                backend == InferenceBackend.NNAPI || backend == InferenceBackend.XNNPACK ||
                backend == InferenceBackend.CPU)) {
            // ONNX Runtime 后端选 .tflite 模型时，映射到对应的 TFLite 后端
            val tfliteBackend = when (backend) {
                InferenceBackend.NNAPI -> InferenceBackend.TFLITE_NNAPI
                InferenceBackend.Auto -> InferenceBackend.TFLITE
                else -> backend // TFLITE/TFLITE_GPU/TFLITE_NNAPI/CPU/XNNPACK 都走 TFLite CPU
            }
            Logger.d("[Route] trying TFLite engine backend=$backend tfliteBackend=$tfliteBackend ...")
            val tflite = TfliteEngine(context)
            tflite.loadModel(modelPath, tfliteBackend)
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
    private var maxProposalsBeforeNms = 200

    /** 复用列表避免每帧分配新 ArrayList */
    private val _rawDetections = ArrayList<DetectionResult>(128)

    /** 检查是否在 DEBUG 日志级别 */
    private val isDebugLogging: Boolean
        get() = Logger.enabled

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
            val stride = numProposals // JIT 友好的 stride 局部变量
            val maxClassIdx = numClasses.coerceAtMost(80)

            // NNAPI 垃圾输出快速检测：采样前 1% proposals 看置信度
            var diagMaxConf = 0f
            val diagStep = (numProposals / 100).coerceAtLeast(1)
            var si = 0
            while (si < numProposals) {
                // 只检查第一个类别（cls0）的置信度做 NNAPI 检测
                // 正常帧 cls0 大概率有值，NNAPI 垃圾帧全部接近 0
                val p = outputArray[4 * stride + si]
                if (p > diagMaxConf) diagMaxConf = p
                si += diagStep
            }

            // NNAPI 垃圾输出检测
            if (diagMaxConf < 0.01f && currentProvider == "NNAPI" && !nnapiGarbageDetected) {
                Logger.w("[Fallback] *** NNAPI output all-zero (diagMaxConf=$diagMaxConf), scheduling CPU fallback ***")
                nnapiGarbageDetected = true
                onPerformanceUpdate(0f, "NNAPI(garbage)", System.currentTimeMillis() - startTime)
                return emptyList()
            }

            // ── 诊断日志（采样频率降至 60 帧，且仅在 DEBUG 级别时输出详细 sample） ──
            diagFrameCount++
            val isDiagFrame = diagFrameCount % DIAG_INTERVAL == 0
            if (isDiagFrame) {
                Logger.d("[D07] Proposals=$numProposals features=$numFeatures classes=$numClasses output=$totalElements maxConf=${"%.4f".format(diagMaxConf)}")

                if (isDebugLogging) {
                    val sampleStr = (0 until 10.coerceAtMost(numProposals)).joinToString { i ->
                        val cx = outputArray[i]
                        val cy = outputArray[1 * stride + i]
                        val w = outputArray[2 * stride + i]
                        val h = outputArray[3 * stride + i]
                        val p0 = outputArray[4 * stride + i]
                        val lastClassIdx = (numClasses - 1).coerceAtLeast(0)
                        val pLast = outputArray[(4 + lastClassIdx) * stride + i]
                        "(${"%.2f".format(cx)},${"%.2f".format(cy)}|${"%.0f".format(w)}x${"%.0f".format(h)}|p0=${"%.4f".format(p0)},pLast=${"%.4f".format(pLast)})"
                    }
                    Logger.d("[Sample] first 10: $sampleStr")
                }
            }

            // ── Letterbox 坐标还原参数 ──
            val padL = result.padLeft.toFloat()
            val padT = result.padTop.toFloat()
            val sc = result.scale
            val origW = result.origWidth.toFloat()
            val origH = result.origHeight.toFloat()

            // ── YOLOv8 解码 + letterbox 坐标还原 ──
            // 复用 rawDetections 列表，避免每帧分配新 ArrayList
            _rawDetections.clear()
            // 快速路径：先检查 box 宽度/高度是否有效，跳过无效 proposal
            // 使用 while 循环 + 底部统一 i++，避免 continue 绕开递增
            var i = 0
            while (i < numProposals) {
                val cx = outputArray[i]
                val cy = outputArray[1 * stride + i]
                val bw = outputArray[2 * stride + i]
                val bh = outputArray[3 * stride + i]

                // 跳过宽高无效或为负的 proposal（加速过滤无效输出）
                if (bw > 0f && bh > 0f) {
                    var maxProb = 0f
                    var maxClassId = 0
                    // 展开内层循环：用 while + 局部变量减少 JIT 边界检查
                    var c = 0
                    while (c < maxClassIdx) {
                        val prob = outputArray[(4 + c) * stride + i]
                        if (prob > maxProb) {
                            maxProb = prob
                            maxClassId = c
                        }
                        c++
                    }

                    if (maxProb >= confidenceThreshold) {
                        // 将 letterbox 空间坐标还原到原始图像空间
                        val cxOrig = (cx - padL) / sc
                        val cyOrig = (cy - padT) / sc
                        val wOrig = bw / sc
                        val hOrig = bh / sc

                        // 归一化到 [0, 1]（相对于原始图像尺寸）
                        val x1 = ((cxOrig - wOrig / 2f) / origW).coerceIn(0f, 1f)
                        val y1 = ((cyOrig - hOrig / 2f) / origH).coerceIn(0f, 1f)
                        val x2 = ((cxOrig + wOrig / 2f) / origW).coerceIn(0f, 1f)
                        val y2 = ((cyOrig + hOrig / 2f) / origH).coerceIn(0f, 1f)

                        // 优先使用模型元数据中的类别名
                        val label = when {
                            eng.classNames.containsKey(maxClassId) -> eng.classNames[maxClassId]!!
                            numClasses == 80 && maxClassId in COCO_CLASSES.indices -> COCO_CLASSES[maxClassId]
                            else -> "class_$maxClassId"
                        }
                        _rawDetections.add(
                            DetectionResult(label, maxClassId, maxProb, x1, y1, x2, y2,
                                frameAspect = origW / origH)
                        )
                    }
                }
                i++
            }

            // NMS 前按置信度取 top-K，减少 O(n²) 开销
            val candidates = if (_rawDetections.size > maxProposalsBeforeNms) {
                _rawDetections.sortedByDescending { it.confidence }.take(maxProposalsBeforeNms)
            } else {
                _rawDetections
            }

            val finalDetections = NmsHelper.nms(candidates, iouThreshold)
            val elapsed = System.currentTimeMillis() - startTime
            val fps = if (elapsed > 0) 1000f / elapsed else 0f

            if (finalDetections.isNotEmpty()) {
                val classes = finalDetections.groupBy { it.label }.mapValues { it.value.size }
                Logger.i("[Result] final=${finalDetections.size} before_nms=${candidates.size} raw=${_rawDetections.size} classes=$classes")
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
