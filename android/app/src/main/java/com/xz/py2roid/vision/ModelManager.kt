package com.xz.py2roid.vision

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "py2roid.ModelManager"
        private const val MODELS_DIR = "models"
        private const val ASSETS_MODELS_DIR = "models"
        // ONNX protobuf: 首字节固定为 0x08 (field=1, wire_type=varint)
        private const val ONNX_MAGIC_FIRST: Byte = 0x08
    }

    data class ModelInfo(
        val name: String,
        val path: String,
        val inputSize: String = "640x640",
        val classes: Int = 80,
        val inputName: String = "images",
        val outputName: String = "output0"
    )

    private val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR)

    /**
     * Extract built-in models from assets/models/ on first launch,
     * then return the model list.
     */
    fun initModels(): List<ModelInfo> {
        val dir = modelsDir
        if (!dir.exists()) {
            dir.mkdirs()
        }

        try {
            val assetsPath = ASSETS_MODELS_DIR
            val assetList = context.assets.list(assetsPath) ?: emptyArray()
            for (assetName in assetList) {
                val targetFile = File(dir, assetName)
                if (targetFile.exists()) {
                    Log.d(TAG, "Skipping existing model: $assetName")
                    continue
                }
                Log.d(TAG, "Extracting model: $assetName")
                context.assets.open("$assetsPath/$assetName").use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Extracted: $assetName -> ${targetFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract models from assets", e)
        }

        return scanModels()
    }

    /**
     * Scan {filesDir}/models/ for all .onnx files and return model info list.
     */
    fun scanModels(): List<ModelInfo> {
        val dir = modelsDir
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }

        return dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".onnx") || it.name.endsWith(".vaim")) }
            ?.map { file ->
                ModelInfo(
                    name = file.name,
                    path = file.absolutePath
                )
            }
            ?: emptyList()
    }

    /**
     * Validate ONNX protobuf header: first 8 bytes must match
     * the protobuf wire type marker.
     */
    fun validateOnnxHeader(file: File): Boolean {
        try {
            if (!file.exists() || file.length() < 4) {
                Log.w(TAG, "File too small or missing: ${file.absolutePath}")
                return false
            }
            val header = ByteArray(1)
            file.inputStream().use { stream ->
                val bytesRead = stream.read(header)
                if (bytesRead < 1) {
                    Log.w(TAG, "Could not read header from ${file.name}")
                    return false
                }
            }
            val valid = header[0] == ONNX_MAGIC_FIRST
            if (!valid) {
                Log.w(TAG, "Invalid ONNX header for ${file.name}: " +
                        "expected first byte 0x08, got 0x${"%02x".format(header[0])}")
            }
            return valid
        } catch (e: Exception) {
            Log.e(TAG, "Error validating ONNX header for ${file.name}", e)
            return false
        }
    }

    /**
     * Import a model from an external InputStream (e.g., file picker).
     * Copies to models directory, validates the header, and returns ModelInfo.
     */
    fun importModel(inputStream: InputStream, fileName: String): ModelInfo? {
        return try {
            val dir = modelsDir
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val targetFile = File(dir, fileName)
            inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!validateOnnxHeader(targetFile)) {
                Log.e(TAG, "Imported file has invalid ONNX header, deleting: $fileName")
                targetFile.delete()
                return null
            }

            val info = ModelInfo(
                name = fileName,
                path = targetFile.absolutePath
            )
            Log.d(TAG, "Imported model: $fileName")
            info
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model: $fileName", e)
            null
        }
    }
}
