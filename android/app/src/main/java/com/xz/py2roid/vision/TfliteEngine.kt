package com.xz.py2roid.vision

import android.content.Context
import com.xz.py2roid.util.Logger
import com.xz.py2roid.ui.InferenceBackend
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite 推理引擎 —— 支持 CPU / GPU(OpenCL) / NNAPI。
 */
class TfliteEngine(private val context: Context) : InferenceEngine {

    companion object {
        private const val TAG = "py2roid.TfliteEngine"

        @Volatile
        private var gpuCompatChecked = false
        @Volatile
        private var gpuAvailable = false

        fun isGpuAvailable(): Boolean {
            if (gpuCompatChecked) return gpuAvailable
            gpuCompatChecked = true
            gpuAvailable = try {
                CompatibilityList().isDelegateSupportedOnThisDevice
            } catch (_: Exception) {
                false
            }
            return gpuAvailable
        }

        /** IEEE 754 half-precision (16-bit) → float */
        fun halfToFloat(halfBits: Int): Float {
            val sign = (halfBits shr 15) and 0x1
            val exp = (halfBits shr 10) and 0x1F
            val mant = halfBits and 0x3FF
            val floatBits = when {
                exp == 0 -> {
                    // subnormal: denormalized
                    if (mant == 0) 0 else {
                        val corrExp = -14
                        val corrMant = mant
                        // normalize mantissa
                        var normMant = corrMant
                        var normExp = corrExp
                        while ((normMant and 0x400) == 0) { normMant = normMant shl 1; normExp-- }
                        normMant = normMant and 0x3FF // remove leading 1
                        val floatExp = (normExp + 127)
                        (sign shl 31) or (floatExp shl 23) or (normMant shl 13)
                    }
                }
                exp == 31 -> {
                    // inf or nan
                    if (mant == 0) (sign shl 31) or 0x7F800000 else (sign shl 31) or 0x7FC00000
                }
                else -> {
                    val floatExp = exp - 15 + 127
                    (sign shl 31) or (floatExp shl 23) or (mant shl 13)
                }
            }
            return java.lang.Float.intBitsToFloat(floatBits)
        }
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnapiDelegate: NnApiDelegate? = null
    private var _provider = "TFLite"
    private var _inputWidth = 640
    private var _inputHeight = 640
    private var _inputChannels = 3
    private var outputSize = 0
    private var inputDataType: DataType = DataType.FLOAT32
    private var outputDataType: DataType = DataType.FLOAT32
    private var inputScale = 1f
    private var inputZeroPoint = 0
    private var outputScale = 1f
    private var outputZeroPoint = 0

    // ── Buffer pool：避免每帧重新分配 ByteBuffer ──
    private var _poolInputBuffer: ByteBuffer? = null
    private var _poolOutputBuffer: ByteBuffer? = null
    private var _poolInputArray: FloatArray? = null   // NHWC 转换结果复用

    override val name get() = "TFLite"
    override val provider get() = _provider
    override val inputWidth get() = _inputWidth
    override val inputHeight get() = _inputHeight
    override val classNames: Map<Int, String> get() = emptyMap()

    fun loadModel(modelPath: String, backend: InferenceBackend) {
        close()
        try {
            val buffer = loadModelFile(modelPath)
            val options = Interpreter.Options()

            when (backend) {
                InferenceBackend.TFLITE_GPU -> {
                    if (!isGpuAvailable()) {
                        Logger.w("GPU not available, fallback CPU")
                        _provider = "TFLite"
                    } else {
                        gpuDelegate = GpuDelegate()
                        options.addDelegate(gpuDelegate)
                        _provider = "TFLite_GPU"
                    }
                }
                InferenceBackend.TFLITE_NNAPI -> {
                    nnapiDelegate = NnApiDelegate()
                    options.addDelegate(nnapiDelegate)
                    // NNAPI 不支持的 op 会回退 CPU，必须设置线程数兜底
                    options.setNumThreads(4)
                    _provider = "TFLite_NNAPI"
                }
                else -> {
                    options.setNumThreads(4)
                    // INT8 / dynamic range 模型无 PAD op 问题，启用 XNNPACK 加速
                    // FP32/FP16 的 onnx2tf 模型若 XNNPACK 失败会自动回退
                    try {
                        options.setUseXNNPACK(true)
                        _provider = "TFLite_XNNPACK"
                    } catch (_: Exception) {
                        options.setUseXNNPACK(false)
                        _provider = "TFLite"
                    }
                }
            }

            interpreter = Interpreter(buffer, options)
            readShapes()
            Logger.i("Model loaded: $modelPath provider=$_provider input=${_inputWidth}x${_inputHeight} output=$outputSize outType=$outputDataType outScale=$outputScale outZp=$outputZeroPoint")
        } catch (e: Exception) {
            val fileSize = try { java.io.File(modelPath).length() } catch (_: Exception) { -1L }
            // GPU delegate 失败时回退 CPU
            if (backend == InferenceBackend.TFLITE_GPU && gpuDelegate != null) {
                Logger.w("GPU delegate failed, fallback CPU")
                closeDelegate()
                loadModel(modelPath, InferenceBackend.TFLITE)
                return
            }
            Logger.e("[LoadModel] ${e::class.simpleName}: ${e.message} backend=$backend fileSize=${fileSize}B model=$modelPath")
            throw e
        }
    }

    override fun loadModel(modelPath: String) = loadModel(modelPath, InferenceBackend.TFLITE)

    override fun infer(inputTensor: FloatArray): FloatArray {
        val interp = interpreter ?: throw IllegalStateException("Model not loaded")

        // TFLite 输入 shape: [1, H, W, 3] (NHWC) vs ONNX [1, 3, H, W] (CHW)
        // CHW→NHWC 布局转换，复用中间数组避免每帧分配
        val needNhwc = _inputChannels == 3
        val totalElems = _inputChannels * _inputHeight * _inputWidth
        val nhwcArray: FloatArray
        if (needNhwc) {
            val pool = _poolInputArray
            nhwcArray = if (pool != null && pool.size >= totalElems) pool else {
                FloatArray(totalElems).also { _poolInputArray = it }
            }
            chwToNhwc(inputTensor, nhwcArray)
        } else {
            nhwcArray = inputTensor
        }

        // ── 复用输入 buffer ──
        val isInputQuantized = inputDataType != DataType.FLOAT32
        val inputElemBytes = when (inputDataType) {
            DataType.INT8, DataType.UINT8 -> 1
            DataType.FLOAT32 -> 4
            else -> 2 // FP16, INT16, etc
        }
        val inputBufSize = totalElems * inputElemBytes
        val inputBuffer = _poolInputBuffer?.takeIf { it.capacity() >= inputBufSize }
            ?: ByteBuffer.allocateDirect(inputBufSize).also { _poolInputBuffer = it }
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()
        inputBuffer.limit(inputBufSize)

        if (isInputQuantized) {
            if (inputElemBytes == 2) {
                for (v in nhwcArray) {
                    val q = ((v / inputScale + inputZeroPoint).toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    inputBuffer.putShort(q.toShort())
                }
            } else {
                for (v in nhwcArray) {
                    val q = (v / inputScale + inputZeroPoint + 0.5f).toInt().coerceIn(0, 255)
                    inputBuffer.put(q.toByte())
                }
            }
        } else {
            inputBuffer.asFloatBuffer().put(nhwcArray)
        }
        inputBuffer.rewind()

        // ── 复用输出 buffer ──
        val isOutputQuantized = outputDataType != DataType.FLOAT32
        val outElemBytes = when (outputDataType) {
            DataType.INT8, DataType.UINT8 -> 1
            DataType.FLOAT32 -> 4
            else -> 2
        }
        val outBufSize = outputSize * outElemBytes
        val outputBuffer = _poolOutputBuffer?.takeIf { it.capacity() >= outBufSize }
            ?: ByteBuffer.allocateDirect(outBufSize).also { _poolOutputBuffer = it }
        outputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.rewind()
        outputBuffer.limit(outBufSize)

        interp.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), mapOf(0 to outputBuffer))

        // ── 读取输出 ──
        outputBuffer.rewind()
        val outArray = FloatArray(outputSize)
        if (isOutputQuantized && outElemBytes == 1) {
            for (i in 0 until outputSize) {
                val q = outputBuffer.get().toInt() and 0xFF
                outArray[i] = (q - outputZeroPoint).toFloat() * outputScale
            }
        } else if (isOutputQuantized && outElemBytes == 2) {
            for (i in 0 until outputSize) {
                val halfBits = outputBuffer.getShort().toInt() and 0xFFFF
                outArray[i] = halfToFloat(halfBits)
            }
            val maxVal = outArray.max()
            Logger.d("[Debug] output fp16: max=$maxVal first10=${outArray.take(10).joinToString { "%.4f".format(it) }}")
        } else {
            outputBuffer.asFloatBuffer().get(outArray)
            val maxVal = outArray.max()
            Logger.d("[Debug] output float: max=$maxVal first10=${outArray.take(10).joinToString { "%.4f".format(it) }}")
        }
        return outArray
    }

    override fun close() {
        closeDelegate()
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
        // 释放 buffer pool（模型重载时清空，避免 buffer size 不匹配）
        _poolInputBuffer = null
        _poolOutputBuffer = null
        _poolInputArray = null
    }

    // ── private ──────────────────────────────────────────────

    private fun loadModelFile(path: String): MappedByteBuffer {
        FileInputStream(path).use { fis ->
            val channel = fis.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }

    private fun readShapes() {
        val interp = interpreter ?: return
        val inTensor = interp.getInputTensor(0)
        val outTensor = interp.getOutputTensor(0)
        val inputShape = inTensor.shape()
        val outputShape = outTensor.shape()

        // 读取量化参数
        inputDataType = inTensor.dataType()
        outputDataType = outTensor.dataType()
        inputScale = inTensor.quantizationParams().scale
        inputZeroPoint = inTensor.quantizationParams().zeroPoint
        outputScale = outTensor.quantizationParams().scale
        outputZeroPoint = outTensor.quantizationParams().zeroPoint

        if (inputShape.size >= 4) {
            when {
                inputShape[1] == 3 -> {
                    // NCHW: [1, 3, H, W]
                    _inputChannels = inputShape[1]
                    _inputHeight = inputShape[2]
                    _inputWidth = inputShape[3]
                }
                inputShape[3] == 3 -> {
                    // NHWC: [1, H, W, 3]
                    _inputHeight = inputShape[1]
                    _inputWidth = inputShape[2]
                    _inputChannels = inputShape[3]
                }
                else -> {
                    _inputHeight = inputShape[1]
                    _inputWidth = inputShape[2]
                    _inputChannels = inputShape[3]
                }
            }
        }

        outputSize = if (outputShape.size >= 2) {
            outputShape[1] * outputShape[2]
        } else {
            outputShape.fold(1) { acc, d -> acc * d }
        }
    }

    /** CHW → NHWC 布局转换，写入预分配的目标数组（避免分配新 FloatArray） */
    private fun chwToNhwc(chw: FloatArray, nhwc: FloatArray) {
        val c = _inputChannels
        val h = _inputHeight
        val w = _inputWidth
        var ci = 0
        while (ci < c) {
            val cOffset = ci * h * w
            var hi = 0
            while (hi < h) {
                val rowOffset = hi * w * c
                var wi = 0
                while (wi < w) {
                    nhwc[rowOffset + wi * c + ci] = chw[cOffset + hi * w + wi]
                    wi++
                }
                hi++
            }
            ci++
        }
    }

    private fun closeDelegate() {
        try { gpuDelegate?.close() } catch (_: Exception) {}
        gpuDelegate = null
        try { nnapiDelegate?.close() } catch (_: Exception) {}
        nnapiDelegate = null
    }
}
