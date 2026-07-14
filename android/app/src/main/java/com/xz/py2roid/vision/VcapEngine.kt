package com.xz.py2roid.vision

import android.content.Context
import com.xz.py2roid.util.Logger
import com.vivo.vcap.VcapInstance
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * VCAP 推理引擎 —— 通过 Java API 调用 vivo VCAP SDK。
 *
 * 支持 .vaim 模型格式（需用 VCAP 离线转换工具从 .onnx 转换）。
 * 运行时：NEON（CPU）/ OPENCL（GPU）
 */
class VcapEngine(private val context: Context) : InferenceEngine {

    companion object {
        private const val TAG = "py2roid.VcapEngine"
        private const val GPU_CACHE_FILE = "vcap_gpubinary.bin"

        // VcapInstance.Runtime ordinal 值（避免 Kotlin IR 兼容性问题）
        const val RUNTIME_NEON = 0
        const val RUNTIME_OPENCL = 1

        @Volatile
        private var sdkChecked = false
        @Volatile
        private var sdkAvailable = false

        /** 检查 VCAP SDK 是否可用（native 库是否加载成功），结果缓存 */
        fun isSdkAvailable(): Boolean {
            if (sdkChecked) return sdkAvailable
            sdkChecked = true
            sdkAvailable = try {
                Class.forName("com.vivo.vcap.VcapInstance")
                true
            } catch (_: ClassNotFoundException) {
                false
            }
            return sdkAvailable
        }
    }

    private var vcapNet: VcapInstance? = null
    private var _provider = "VCAP"
    private var _inputWidth = 640
    private var _inputHeight = 640
    private var _inputChannels = 3
    private var _outputName = "output0"
    private var _inputName = "images"
    private var _outputSize = 0

    override val name get() = "VCAP"
    override val provider get() = _provider
    override val inputWidth get() = _inputWidth
    override val inputHeight get() = _inputHeight
    override val classNames: Map<Int, String> get() = emptyMap()

    /**
     * 加载 .vaim 模型。
     * @param modelPath .vaim 文件绝对路径
     */
    override fun loadModel(modelPath: String) = loadModel(modelPath, RUNTIME_NEON)

    @Suppress("UNCHECKED_CAST")
    fun loadModel(modelPath: String, runtimeId: Int) {
        close()
        try {
            val modelBuffer = mapModelFile(modelPath)
            val gpuCachePath = java.io.File(context.cacheDir, GPU_CACHE_FILE).absolutePath

            // 通过反射获取 VcapInstance.Runtime 枚举值（避免 Kotlin IR 兼容性问题）
            val runtimeClass = Class.forName("com.vivo.vcap.VcapInstance\$Runtime")
            val runtimeEnum = (runtimeClass.enumConstants ?: throw IllegalStateException("VcapInstance.Runtime enum not found"))[runtimeId] as Enum<*>

            val net = VcapInstance()
            val builder = net.setRuntime(runtimeEnum as VcapInstance.Runtime)
            val success = builder
                .setModelFile(modelBuffer)
                .setGpuCachePath(gpuCachePath)
                .build()

            if (!success) {
                throw RuntimeException("VCAP build() returned false")
            }

            vcapNet = net

            // 读取输入输出 shape
            try {
                val inputShape = net.getInputShape(_inputName)
                if (inputShape != null && inputShape.size >= 4) {
                    _inputChannels = inputShape[1]
                    _inputHeight = inputShape[2]
                    _inputWidth = inputShape[3]
                }
            } catch (e: Exception) {
                Logger.w("[Shape] input: ${e::class.simpleName}: ${e.message} (defaults ${_inputWidth}x${_inputHeight})")
            }

            try {
                val outputShape = net.getOutputShape(_outputName)
                if (outputShape != null) {
                    _outputSize = outputShape.fold(1) { acc, v -> acc * v }
                }
            } catch (e: Exception) {
                Logger.w("[Shape] output: ${e::class.simpleName}: ${e.message}")
            }

            _provider = when (runtimeId) {
                RUNTIME_NEON -> "VCAP_NEON"
                RUNTIME_OPENCL -> "VCAP_OPENCL"
                else -> "VCAP"
            }

            Logger.i("Model loaded: $modelPath runtime=$_provider input=${_inputWidth}x${_inputHeight} outputSize=$_outputSize")
        } catch (e: Exception) {
            val fileSize = try { java.io.File(modelPath).length() } catch (_: Exception) { -1L }
            Logger.e("[LoadModel] ${e::class.simpleName}: ${e.message} fileSize=${fileSize}B runtime=$runtimeId model=$modelPath")
            throw e
        }
    }

    override fun infer(inputTensor: FloatArray): FloatArray {
        val net = vcapNet ?: throw IllegalStateException("VCAP model not loaded")
        val byteSize = inputTensor.size * 4

        net.setInput(_inputName, inputTensor, 1, _inputChannels, _inputHeight, _inputWidth, byteSize)
        net.forward()

        val outputSize = if (_outputSize > 0) _outputSize else 705600 // YOLOv8 default
        val output = FloatArray(outputSize)
        net.getOutput(_outputName, output)
        return output
    }

    override fun close() {
        try {
            vcapNet?.release()
        } catch (e: Exception) {
            Logger.w("[Release] ${e::class.simpleName}: ${e.message}")
        }
        vcapNet = null
    }

    private fun mapModelFile(modelPath: String): MappedByteBuffer {
        val file = java.io.File(modelPath)
        return FileInputStream(file).use { inputStream ->
            inputStream.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }
    }
}
