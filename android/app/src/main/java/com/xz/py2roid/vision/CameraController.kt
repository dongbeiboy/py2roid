package com.xz.py2roid.vision

import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

class CameraController(
    private val context: android.content.Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrame: (ImageProxy) -> Unit,
    private val onError: (Throwable) -> Unit
) {

    companion object {
        private const val TAG = "py2roid.CameraController"
    }

    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "py2roid-analysis-thread")
    }

    fun startCamera(analyzerTargetSize: Size = Size(640, 480)) {
        Log.d(TAG, "[C05] startCamera called, lensFacing=$lensFacing")
        try {
            val future = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture = future

            future.addListener(
                {
                    try {
                        val provider = future.get()
                        cameraProvider = provider
                        bindUseCases(provider, analyzerTargetSize)
                    } catch (e: Exception) {
                        Log.e(TAG, "[C01] Failed to get camera provider", e)
                        onError(e)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        } catch (e: Exception) {
            Log.e(TAG, "[C02] Failed to start camera", e)
            onError(e)
        }
    }

    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
            cameraProviderFuture = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping camera", e)
        }
    }

    fun setCameraSelector(lensFacing: Int) {
        this.lensFacing = lensFacing
        cameraProvider?.let { provider ->
            try {
                val targetSize = Size(640, 480) // reuse default
                bindUseCases(provider, targetSize)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch camera", e)
                onError(e)
            }
        }
    }

    private fun bindUseCases(provider: ProcessCameraProvider, targetSize: Size) {
        // Unbind all existing use cases first
        provider.unbindAll()

        try {
            // Preview use case
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // Camera selector
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            // Image analysis use case
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(targetSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                try {
                    onFrame(imageProxy)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame analysis error", e)
                    // onFrame 异常时确保释放，double-close 安全
                    try { imageProxy.close() } catch (_: Exception) {}
                }
            }

            // Bind to lifecycle
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )

            Log.d(TAG, "Camera bound successfully, lensFacing=$lensFacing")
        } catch (e: Exception) {
            Log.e(TAG, "[C04] Failed to bind camera use cases", e)
            onError(e)
        }
    }

    /**
     * Clean up resources. Call when the owning component is destroyed.
     */
    fun destroy() {
        stopCamera()
        analysisExecutor.shutdown()
    }
}
