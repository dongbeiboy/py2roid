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

        // TFLite 输入 shape: [1, H, W, 3] or [1, 3, H, W]
        // ONNX 输入是 [1, 3, H, W] CHW，TFLite 默认 NHWC
        val chwArray = inputTensor
        val nhwcArray = if (_inputChannels == 3) {
            chwToNhwc(chwArray)
        } else {
            chwArray
        }

        // ── 构建输入 buffer ──
        val inputBuffer: ByteBuffer
        val isInputQuantized = inputDataType != DataType.FLOAT32
        // 用 elemBytes 判断是否为非标准量化类型（如 FP16）
        val inputElemBytes = if (inputDataType == DataType.INT8 || inputDataType == DataType.UINT8) 1 else if (!isInputQuantized) 4 else 2
        if (isInputQuantized) {
            val bufSize = nhwcArray.size * inputElemBytes
            Logger.i("[Debug] quant input: type=$inputDataType elemBytes=$inputElemBytes bufSize=$bufSize scale=$inputScale zp=$inputZeroPoint")
            inputBuffer = ByteBuffer.allocateDirect(bufSize)
            inputBuffer.order(ByteOrder.nativeOrder())
            if (inputElemBytes == 2) {
                // FP16 或 INT16: 每元素 2 字节，用 putShort 写入
                for (v in nhwcArray) {
                    val q = ((v / inputScale + inputZeroPoint).toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    inputBuffer.putShort(q.toShort())
                }
            } else {
                for (v in nhwcArray) {
                    val q = (v / inputScale + inputZeroPoint + 0.5f).toInt()
                    inputBuffer.put(q.toByte())
                }
            }
        } else {
            val bufSize = nhwcArray.size * 4
            Logger.i("[Debug] float input: bufSize=$bufSize")
            inputBuffer = ByteBuffer.allocateDirect(bufSize)
            inputBuffer.order(ByteOrder.nativeOrder())
            inputBuffer.asFloatBuffer().put(nhwcArray)
        }
        inputBuffer.rewind()

        // ── 构建输出 buffer ──
        val isOutputQuantized = outputDataType != DataType.FLOAT32
        val outElemBytes = if (outputDataType == DataType.INT8 || outputDataType == DataType.UINT8) 1 else if (!isOutputQuantized) 4 else 2
        val outBufSize = outputSize * outElemBytes
        Logger.i("[Debug] output: type=$outputDataType elemBytes=$outElemBytes bufSize=$outBufSize scale=$outputScale zp=$outputZeroPoint")
        val outputBuffer = ByteBuffer.allocateDirect(outBufSize)
        outputBuffer.order(ByteOrder.nativeOrder())

        val outputs = java.util.HashMap<Int, Any>()
        outputs[0] = outputBuffer
        interp.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), outputs)

        // ── 读取输出 ──
        outputBuffer.rewind()
        val outArray = FloatArray(outputSize)
        if (isOutputQuantized && outElemBytes == 1) {
            var maxVal = 0f
            var sumVal = 0f
            for (i in 0 until outputSize) {
                val q = outputBuffer.get().toInt() and 0xFF // unsigned byte
                val v = (q - outputZeroPoint).toFloat() * outputScale
                outArray[i] = v
                if (v > maxVal) maxVal = v
                sumVal += v
            }
            Logger.i("[Debug] output dequant: max=$maxVal avg=${sumVal / outputSize} first10=${outArray.take(10).joinToString { "%.4f".format(it) }}")
        } else if (isOutputQuantized && outElemBytes == 2) {
            // FP16/INT16 输出：每 2 字节读取 half/short → 转 float
            for (i in 0 until outputSize) {
                val halfBits = outputBuffer.getShort().toInt() and 0xFFFF
                outArray[i] = halfToFloat(halfBits)
            }
            val maxVal = outArray.max()
            Logger.i("[Debug] output fp16: max=$maxVal first10=${outArray.take(10).joinToString { "%.4f".format(it) }}")
        } else {
            outputBuffer.asFloatBuffer().get(outArray)
            val maxVal = outArray.max()
            Logger.i("[Debug] output float: max=$maxVal first10=${outArray.take(10).joinToString { "%.4f".format(it) }}")
        }
        return outArray
    }

    override fun close() {
        closeDelegate()
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
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

    private fun chwToNhwc(chw: FloatArray): FloatArray {
        val c = _inputChannels
        val h = _inputHeight
        val w = _inputWidth
        val nhwc = FloatArray(c * h * w)
        for (ci in 0 until c) {
            for (hi in 0 until h) {
                for (wi in 0 until w) {
                    nhwc[hi * w * c + wi * c + ci] = chw[ci * h * w + hi * w + wi]
                }
            }
        }
        return nhwc
    }

    private fun closeDelegate() {
        try { gpuDelegate?.close() } catch (_: Exception) {}
        gpuDelegate = null
        try { nnapiDelegate?.close() } catch (_: Exception) {}
        nnapiDelegate = null
    }
}
