package com.xz.py2roid.vision

import android.content.Context
import android.util.Log
import com.xz.py2roid.ui.InferenceBackend
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
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
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnapiDelegate: NnApiDelegate? = null
    private var _provider = "TFLite"
    private var _inputWidth = 640
    private var _inputHeight = 640
    private var _inputChannels = 3
    private var outputSize = 0

    override val name get() = "TFLite"
    override val provider get() = _provider
    override val inputWidth get() = _inputWidth
    override val inputHeight get() = _inputHeight

    fun loadModel(modelPath: String, backend: InferenceBackend) {
        close()
        try {
            val buffer = loadModelFile(modelPath)
            val options = Interpreter.Options()

            when (backend) {
                InferenceBackend.TFLITE_GPU -> {
                    if (!isGpuAvailable()) {
                        Log.w(TAG, "GPU not available, fallback CPU")
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
                    _provider = "TFLite_NNAPI"
                }
                else -> {
                    options.setNumThreads(4)
                    _provider = "TFLite"
                }
            }

            interpreter = Interpreter(buffer, options)
            readShapes()
            Log.i(TAG, "Model loaded: $modelPath provider=$_provider input=${_inputWidth}x${_inputHeight} output=$outputSize")
        } catch (e: Exception) {
            // GPU delegate 失败时回退 CPU
            if (backend == InferenceBackend.TFLITE_GPU && gpuDelegate != null) {
                Log.w(TAG, "GPU delegate failed, fallback CPU", e)
                closeDelegate()
                loadModel(modelPath, InferenceBackend.TFLITE)
                return
            }
            Log.e(TAG, "Failed to load model: $modelPath", e)
            throw e
        }
    }

    override fun loadModel(modelPath: String) = loadModel(modelPath, InferenceBackend.TFLITE)

    override fun infer(inputTensor: FloatArray): FloatArray {
        val interp = interpreter ?: throw IllegalStateException("Model not loaded")

        // TFLite 输入 shape: [1, H, W, 3] or [1, 3, H, W]
        // ONNX 输入是 [1, 3, H, W] CHW，TFLite 默认 NHWC
        // 需要从 CHW 转为 NHWC
        val chwArray = inputTensor
        val nhwcArray = if (_inputChannels == 3) {
            chwToNhwc(chwArray)
        } else {
            chwArray
        }

        val inputShape = intArrayOf(1, _inputHeight, _inputWidth, _inputChannels)
        val outputArray = Array(1) { FloatArray(outputSize) }

        interp.run(nhwcArray, outputArray)

        // 输出保持原始格式（Detector 负责解码）
        return outputArray[0]
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
        val inputShape = interp.getInputTensor(0).shape()
        val outputShape = interp.getOutputTensor(0).shape()

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
