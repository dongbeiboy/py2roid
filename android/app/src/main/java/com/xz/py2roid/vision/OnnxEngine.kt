package com.xz.py2roid.vision

import android.content.Context
import com.xz.py2roid.util.Logger
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import com.xz.py2roid.ui.InferenceBackend
import java.nio.FloatBuffer

/**
 * ONNX Runtime 推理引擎 —— 封装 OrtSession 的创建、推理和销毁。
 */
class OnnxEngine(private val context: Context) : InferenceEngine {

    companion object {
        private const val TAG = "py2roid.OnnxEngine"
    }

    private var session: OrtSession? = null
    private var _provider = "CPU"
    private var _inputWidth = 640
    private var _inputHeight = 640

    private val _classNames = mutableMapOf<Int, String>()

    override val name get() = "ONNX Runtime"
    override val provider get() = _provider
    override val inputWidth get() = _inputWidth
    override val inputHeight get() = _inputHeight
    override val classNames: Map<Int, String> get() = _classNames

    fun loadModel(modelPath: String, backend: InferenceBackend) {
        close()
        try {
            val ortEnv = OrtEnvironment.getEnvironment()
            Logger.d("[Load] backend=$backend model=$modelPath")

            val resolvedBackend = if (backend == InferenceBackend.Auto) {
                val rec = DeviceProfile.getRecommendedBackend(context)
                Logger.d("[Load] Auto resolved -> $rec")
                rec
            } else {
                backend.also { Logger.d("[Load] explicit backend=$it") }
            }

            Logger.d("[Load] creating SessionOptions backend=$resolvedBackend")
            val opts = createSessionOptions(resolvedBackend)
            Logger.d("[Load] SessionOptions created, provider pre-session=$_provider")

            Logger.d("[Load] calling createSession ...")
            session = try {
                ortEnv.createSession(modelPath, opts).also {
                    Logger.i("[Load] createSession OK provider=$_provider")
                }
            } catch (e: Exception) {
                Logger.w("[CreateSession] ${e::class.simpleName}: ${e.message} backend=$resolvedBackend -> fallback CPU")
                _provider = "CPU"
                ortEnv.createSession(modelPath, OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }).also {
                    Logger.i("[Load] createSession (CPU fallback) OK")
                }
            }
            readModelMetadata()
            Logger.i("[Load] Done: provider=$_provider input=${_inputWidth}x${_inputHeight}")
        } catch (e: Exception) {
            val fileSize = try { java.io.File(modelPath).length() } catch (_: Exception) { -1L }
            Logger.e("[LoadModel] ${e::class.simpleName}: ${e.message} provider=$_provider fileSize=${fileSize}B model=$modelPath")
            throw e
        }
    }

    override fun loadModel(modelPath: String) = loadModel(modelPath, InferenceBackend.CPU)

    override fun infer(inputTensor: FloatArray): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val sess = session ?: throw IllegalStateException("Model not loaded")
        val shape = longArrayOf(1L, 3L, _inputHeight.toLong(), _inputWidth.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputTensor), shape)
        val inName = sess.inputNames.firstOrNull() ?: "images"
        val result = sess.run(mapOf(inName to tensor))
        tensor.close()
        val outTensor = result.get(0) as? OnnxTensor
            ?: throw OrtException("Output tensor not found")
        val outData = outTensor.floatBuffer
        val outArray = FloatArray(outData.remaining())
        outData.get(outArray)
        outTensor.close()
        result.close()
        return outArray
    }

    override fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    // ── private ──────────────────────────────────────────────

    private fun createSessionOptions(backend: InferenceBackend): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            when (backend) {
                InferenceBackend.CPU -> {
                    setIntraOpNumThreads(4)
                    _provider = "CPU"
                    Logger.d("[SessionOpt] CPU, 4 threads")
                }
                InferenceBackend.XNNPACK -> {
                    setIntraOpNumThreads(4)
                    try {
                        addXnnpack(emptyMap())
                        _provider = "XNNPACK"
                        Logger.i("[SessionOpt] XNNPACK OK")
                    } catch (e: Exception) {
                        _provider = "CPU"
                        Logger.w("[SessionOpt] XNNPACK ${e::class.simpleName}: ${e.message} -> CPU")
                    }
                }
                InferenceBackend.NNAPI -> {
                    try {
                        addNnapi()
                        _provider = "NNAPI"
                        Logger.i("[SessionOpt] NNAPI OK")
                    } catch (e: Exception) {
                        setIntraOpNumThreads(4)
                        _provider = "CPU"
                        Logger.w("[SessionOpt] NNAPI ${e::class.simpleName}: ${e.message} -> CPU")
                    }
                }
                InferenceBackend.VCAP, InferenceBackend.Auto -> {
                    try {
                        addNnapi()
                        _provider = "NNAPI"
                        Logger.i("[SessionOpt] VCAP/Auto -> NNAPI OK")
                    } catch (e: Exception) {
                        setIntraOpNumThreads(4)
                        _provider = "CPU"
                        Logger.w("[SessionOpt] VCAP/Auto -> NNAPI ${e::class.simpleName}: ${e.message} -> CPU")
                    }
                }
                InferenceBackend.TFLITE, InferenceBackend.TFLITE_GPU, InferenceBackend.TFLITE_NNAPI -> {
                    setIntraOpNumThreads(4)
                    _provider = "CPU"
                }
            }
        }
    }

    private fun readModelMetadata() {
        val sess = session ?: return
        try {
            val inputInfo = sess.inputInfo.entries.first().value
            val infoStr = inputInfo.info.toString()
            val dims = Regex("(\\d+)").findAll(infoStr).map { it.value.toInt() }.toList()
            if (dims.size >= 4) {
                _inputHeight = dims[2]
                _inputWidth = dims[3]
            }

            // 从 ONNX 自定义元数据读取类别名（Ultralytics 导出时自动嵌入）
            val meta = sess.metadata
            val customMeta = meta.customMetadata
            val namesStr = customMeta["names"]
            if (namesStr != null) {
                // 格式: {0: 'miku', 1: 'teto'}
                Regex("(\\d+):\\s*'([^']+)'").findAll(namesStr).forEach { match ->
                    val id = match.groupValues[1].toIntOrNull()
                    val name = match.groupValues[2]
                    if (id != null) {
                        _classNames[id] = name
                    }
                }
                Logger.i("Read class names from model: $_classNames")
            }
        } catch (e: Exception) {
            Logger.w("Could not read model metadata (${e.message})")
        }
    }
}
