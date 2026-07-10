package com.xz.py2roid.vision

import android.content.Context
import android.util.Log
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

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var _provider = "CPU"
    private var _inputWidth = 640
    private var _inputHeight = 640

    override val name get() = "ONNX Runtime"
    override val provider get() = _provider
    override val inputWidth get() = _inputWidth
    override val inputHeight get() = _inputHeight

    fun loadModel(modelPath: String, backend: InferenceBackend) {
        close()
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val resolvedBackend = if (backend == InferenceBackend.Auto) {
                DeviceProfile.getRecommendedBackend(context)
            } else backend

            val opts = createSessionOptions(resolvedBackend)
            session = try {
                ortEnv!!.createSession(modelPath, opts)
            } catch (e: Exception) {
                if (resolvedBackend != InferenceBackend.CPU) {
                    Log.w(TAG, "Session creation failed with $resolvedBackend, retrying CPU", e)
                    _provider = "CPU"
                    ortEnv!!.createSession(modelPath, OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(4)
                    })
                } else throw e
            }
            readModelMetadata()
            Log.i(TAG, "Model loaded: $modelPath provider=$_provider input=${_inputWidth}x${_inputHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $modelPath", e)
            throw e
        }
    }

    override fun loadModel(modelPath: String) = loadModel(modelPath, InferenceBackend.CPU)

    override fun infer(inputTensor: FloatArray): FloatArray {
        val env = ortEnv ?: throw IllegalStateException("Model not loaded")
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
        ortEnv = null
    }

    // ── private ──────────────────────────────────────────────

    private fun createSessionOptions(backend: InferenceBackend): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            when (backend) {
                InferenceBackend.CPU -> {
                    setIntraOpNumThreads(4)
                    _provider = "CPU"
                }
                InferenceBackend.XNNPACK -> {
                    setIntraOpNumThreads(4)
                    try {
                        addXnnpack(emptyMap())
                        _provider = "XNNPACK"
                    } catch (e: Exception) {
                        _provider = "CPU"
                        Log.w(TAG, "XNNPACK unavailable, fallback CPU")
                    }
                }
                InferenceBackend.NNAPI -> {
                    addNnapi()
                    _provider = "NNAPI"
                }
                InferenceBackend.VCAP, InferenceBackend.Auto -> {
                    try {
                        addNnapi()
                        _provider = "NNAPI"
                    } catch (e: Exception) {
                        setIntraOpNumThreads(4)
                        _provider = "CPU"
                        Log.w(TAG, "NNAPI unavailable in Auto/VCAP mode, fallback CPU")
                    }
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
        } catch (e: Exception) {
            Log.w(TAG, "Could not read input shape, using defaults", e)
        }
    }
}
