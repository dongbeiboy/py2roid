package com.xz.py2roid.vision

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import com.xz.py2roid.ui.InferenceBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.exp

class Detector(
    private val context: Context,
    private val modelManager: ModelManager,
    private val imagePreprocessor: ImagePreprocessor,
    private val onDetectionResult: (List<DetectionResult>) -> Unit,
    private val onPerformanceUpdate: (fps: Float, provider: String, frameTimeMs: Long) -> Unit
) {

    companion object {
        private const val TAG = "py2roid.Detector"

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
    private var ortEnvironment: OrtEnvironment? = null

    @Volatile
    private var ortSession: OrtSession? = null

    @Volatile
    var confidenceThreshold: Float = 0.5f

    @Volatile
    var iouThreshold: Float = 0.45f

    @Volatile
    var currentProvider: String = "CPU"
        private set

    private var inputNames: Array<out String> = emptyArray()
    private var outputNames: Array<out String> = emptyArray()
    private var modelInputWidth: Int = 640
    private var modelInputHeight: Int = 640

    /**
     * Load a model from the given path and configure the specified backend.
     * Thread-safe via @Synchronized.
     */
    @Synchronized
    fun loadModel(modelPath: String, backend: InferenceBackend) {
        try {
            // Close existing session/environment
            closeSession()

            ortEnvironment = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                when (backend) {
                    InferenceBackend.Auto -> {
                        val recommended = DeviceProfile.getRecommendedBackend(context)
                        Log.i(TAG, "[D01] Auto mode: recommended=$recommended")
                        currentProvider = recommended.name
                        when (recommended) {
                            InferenceBackend.NNAPI -> addNnapi()
                            else -> setIntraOpNumThreads(4)
                        }
                    }
                    InferenceBackend.CPU -> {
                        setIntraOpNumThreads(4)
                        currentProvider = "CPU"
                    }
                    InferenceBackend.NNAPI -> {
                        addNnapi()
                        currentProvider = "NNAPI"
                    }
                    InferenceBackend.VCAP -> {
                        setIntraOpNumThreads(4)
                        currentProvider = "CPU"
                        Log.w(TAG, "[D02] VCAP not supported, fallback CPU")
                    }
                }
            }

            try {
                ortSession = ortEnvironment!!.createSession(modelPath, sessionOptions)
            } catch (e: Exception) {
                val shouldRetryCpu = when (backend) {
                    InferenceBackend.NNAPI -> true
                    InferenceBackend.Auto -> currentProvider == "NNAPI"
                    else -> false
                }
                if (shouldRetryCpu) {
                    Log.w(TAG, "NNAPI session creation failed, falling back to CPU", e)
                    currentProvider = "CPU"
                    val cpuOptions = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(4)
                    }
                    ortSession = ortEnvironment!!.createSession(modelPath, cpuOptions)
                } else {
                    throw e
                }
            }

            // Read input/output metadata from the session
            val session = ortSession!!
            inputNames = session.inputNames.toTypedArray()
            outputNames = session.outputNames.toTypedArray()

            // Attempt to infer input size from session metadata
            try {
                val inputInfo = session.inputInfo.entries.first().value
                val infoStr = inputInfo.info.toString()
                // Parse shape from info string like "TensorType{shape=[?,3,640,640]}"
                val dims = Regex("(\\d+)").findAll(infoStr).map { it.value.toInt() }.toList()
                if (dims.size >= 4) {
                    modelInputHeight = dims[2]
                    modelInputWidth = dims[3]
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read input shape from session, using defaults", e)
                modelInputWidth = 640
                modelInputHeight = 640
            }

            Log.d(TAG, "Model loaded: $modelPath, backend=$backend, " +
                    "input=${modelInputWidth}x$modelInputHeight, " +
                    "inputNames=${inputNames.joinToString()}, " +
                    "outputNames=${outputNames.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "[D04] Failed to load model: $modelPath", e)
            throw e
        }
    }

    /**
     * Run inference on a preprocessed float array.
     * Thread-safe via @Synchronized.
     */
    @Synchronized
    fun detect(inputTensor: FloatArray, inputWidth: Int, inputHeight: Int): List<DetectionResult> {
        val env = ortEnvironment ?: throw IllegalStateException("Model not loaded")
        val session = ortSession ?: throw IllegalStateException("Model not loaded")

        val startTime = System.currentTimeMillis()

        try {
            val shape = longArrayOf(1L, 3L, inputHeight.toLong(), inputWidth.toLong())
            val buffer = FloatBuffer.wrap(inputTensor)
            val tensor = OnnxTensor.createTensor(env, buffer, shape)

            // 从 session 获取第一个 input name
            val inName = session.inputNames.firstOrNull() ?: "images"
            val output = session.run(
                mapOf(inName to tensor)
            )

            tensor.close()

            // 从 run 结果中取第一个输出名称（Result 用 iterator 遍历）
            val outName = output.iterator().next()
            Log.d(TAG, "[D06] Using output: $outName")

            // 按索引取第一个输出 tensor（get(Int) 直接返回 OnnxValue，无 Optional 包装）
            val outputTensor = output.get(0) as? OnnxTensor
                ?: throw OrtException("Output tensor not found for: $outName")

            val outputData = outputTensor.floatBuffer
            val outputArray = FloatArray(outputData.remaining())
            outputData.get(outputArray)
            outputTensor.close()
            output.close()

            // 输出解析：根据实际元素数判断格式
            val totalElements = outputArray.size
            Log.d(TAG, "[D07] Output elements: $totalElements")
            if (totalElements < 84) {
                Log.w(TAG, "[D08] Output too small, skipping frame")
                return emptyList()
            }

            // YOLOv8 标准输出 [1, 84, 8400] = 705,600 floats
            // cx,cy,w,h 是像素坐标（640 空间），类概率已含 sigmoid
            val numProposals = if (totalElements == 705600) 8400 else {
                // 其他格式尝试按 85 维解析
                totalElements / 85
            }
            val numFeatures = totalElements / numProposals
            val numClasses = numFeatures - 4
            Log.d(TAG, "[D07] Proposals=$numProposals features=$numFeatures classes=$numClasses")

            val detections = mutableListOf<DetectionResult>()

            for (i in 0 until numProposals) {
                val cx = outputArray[i]
                val cy = outputArray[1 * numProposals + i]
                val w = outputArray[2 * numProposals + i]
                val h = outputArray[3 * numProposals + i]

                // 类概率：YOLOv8 输出自带 sigmoid，直接取
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

                // 坐标归一化到 [0,1]
                val x1 = ((cx - w / 2f) / inputWidth).coerceIn(0f, 1f)
                val y1 = ((cy - h / 2f) / inputHeight).coerceIn(0f, 1f)
                val x2 = ((cx + w / 2f) / inputWidth).coerceIn(0f, 1f)
                val y2 = ((cy + h / 2f) / inputHeight).coerceIn(0f, 1f)

                val label = if (maxClassId in COCO_CLASSES.indices) {
                    COCO_CLASSES[maxClassId]
                } else {
                    "class_$maxClassId"
                }

                detections.add(
                    DetectionResult(
                        label = label,
                        classId = maxClassId,
                        confidence = maxProb,
                        x1 = x1.coerceIn(0f, 1f),
                        y1 = y1.coerceIn(0f, 1f),
                        x2 = x2.coerceIn(0f, 1f),
                        y2 = y2.coerceIn(0f, 1f)
                    )
                )
            }

            // Apply NMS
            val finalDetections = NmsHelper.nms(detections, iouThreshold, confidenceThreshold)

            val elapsed = System.currentTimeMillis() - startTime
            val fps = if (elapsed > 0) 1000f / elapsed else 0f

            onDetectionResult(finalDetections)
            onPerformanceUpdate(fps, currentProvider, elapsed)

            return finalDetections
        } catch (e: Exception) {
            Log.e(TAG, "[D05] Detection failed: ${e.message}", e)
            val elapsed = System.currentTimeMillis() - startTime
            onPerformanceUpdate(0f, currentProvider, elapsed)
            throw e
        }
    }

    /**
     * Release all native resources.
     */
    @Synchronized
    fun close() {
        closeSession()
        imagePreprocessor.close()
    }

    private fun closeSession() {
        try {
            ortSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing session", e)
        }
        ortSession = null
        ortEnvironment = null
    }

    private fun sigmoid(x: Float): Float {
        return 1.0f / (1.0f + exp(-x))
    }
}
