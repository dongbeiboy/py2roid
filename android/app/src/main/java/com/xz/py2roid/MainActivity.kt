package com.xz.py2roid

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xz.py2roid.bridge.PythonBridge
import com.xz.py2roid.service.DetectionForegroundService
import com.xz.py2roid.ui.BoundingBox
import com.xz.py2roid.ui.HudInfo
import com.xz.py2roid.ui.MainScreen
import com.xz.py2roid.ui.MainViewModel
import com.xz.py2roid.ui.Py2roidTheme
import com.xz.py2roid.util.Logger
import com.xz.py2roid.util.OriginOsHelper
import com.xz.py2roid.util.PermissionHelper
import com.xz.py2roid.util.SettingsStore
import com.xz.py2roid.vision.CameraController
import com.xz.py2roid.vision.Detector
import com.xz.py2roid.vision.ImagePreprocessor
import com.xz.py2roid.vision.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var modelManager: ModelManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var permissionHelper: PermissionHelper
    private var cameraController: CameraController? = null
    private var detector: Detector? = null
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        modelManager = ModelManager(this)
        settingsStore = SettingsStore(this)
        permissionHelper = PermissionHelper(this)

        // 解压内置模型
        lifecycleScope.launch(Dispatchers.IO) {
            modelManager.initModels()
            Logger.d("Models initialized")
        }

        // 初始化 Python 桥接
        lifecycleScope.launch(Dispatchers.IO) {
            PythonBridge.init(mapOf("log_level" to "INFO"))
        }

        setContent {
            val viewModel: MainViewModel = viewModel()
            val previewView by viewModel.previewView.collectAsState()

            // 当 previewView 就绪且权限已授予，启动相机
            if (previewView != null && permissionHelper.hasCamera() && !isRunning) {
                isRunning = true
                lifecycleScope.launch {
                    startDetection(viewModel, previewView!!)
                }
            }

            Py2roidTheme {
                MainScreen(viewModel = viewModel)
            }
        }

        // 权限请求（触发点）
        permissionHelper.requestCamera {
            Logger.i("Camera permission granted, ready to start")
        }

        // OriginOS 检测
        if (OriginOsHelper.isOriginOs()) {
            Logger.d("OriginOS detected")
        }
    }

    private fun startDetection(viewModel: MainViewModel, previewView: androidx.camera.view.PreviewView) {
        val settings = settingsStore.load()

        // 创建 Detector
        val det = Detector(
            context = this,
            modelManager = modelManager,
            imagePreprocessor = ImagePreprocessor,
            onDetectionResult = { results ->
                val boxes = results.map { r ->
                    BoundingBox(
                        x1 = r.x1, y1 = r.y1, x2 = r.x2, y2 = r.y2,
                        label = r.label, confidence = r.confidence,
                        color = labelColors[r.label] ?: androidx.compose.ui.graphics.Color(0xFF2196F3)
                    )
                }
                viewModel.updateBoxes(boxes)
                viewModel.updateHud(HudInfo(
                    fps = 0f,
                    targetCount = results.size,
                    provider = this.detector?.currentProvider ?: "CPU",
                    commState = "离线"
                ))
            },
            onPerformanceUpdate = { fps, provider, frameTimeMs ->
                viewModel.updateHud(viewModel.hudInfo.value.copy(
                    fps = fps, provider = provider, frameTimeMs = frameTimeMs
                ))
            }
        )
        detector = det

        // 加载模型
        try {
            val modelName = settingsStore.getSelectedModel()
            val models = modelManager.scanModels()
            val modelPath = models.find { it.name == modelName }?.path
            if (modelPath != null) {
                det.confidenceThreshold = settings.confidenceThreshold
                det.iouThreshold = settings.iouThreshold
                det.loadModel(modelPath, settings.inferenceBackend)
                Logger.i("Model loaded: $modelName")
            } else {
                Logger.w("Model not found: $modelName")
                return
            }
        } catch (e: Exception) {
            Logger.e("Failed to load model", e)
            return
        }

        // 创建 CameraController
        val cam = CameraController(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            onFrame = { imageProxy ->
                val image = imageProxy.image
                if (image != null) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.Default) {
                            try {
                                val tensor = ImagePreprocessor.preprocess(image)
                                det.detect(tensor, 640, 640)
                            } catch (e: Exception) {
                                Logger.e("Frame processing error", e)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }
                } else {
                    imageProxy.close()
                }
            },
            onError = { e -> Logger.e("Camera error", e) }
        )
        cameraController = cam
        cam.startCamera()

        // 启动前台服务
        startForegroundService()

        viewModel.setDetecting(true)
        Logger.i("Detection started")
    }

    private fun startForegroundService() {
        try {
            val intent = Intent(this, DetectionForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Logger.e("Failed to start foreground service", e)
        }
    }

    override fun onPause() {
        super.onPause()
        cameraController?.stopCamera()
    }

    override fun onResume() {
        super.onResume()
        // CameraX 会在 lifecycle resume 时自动恢复
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraController?.destroy()
        try {
            stopService(Intent(this, DetectionForegroundService::class.java))
        } catch (_: Exception) {}
    }

    companion object {
        private val labelColors = mapOf(
            "person" to androidx.compose.ui.graphics.Color(0xFFE53935),
            "car" to androidx.compose.ui.graphics.Color(0xFFFF9800),
            "bicycle" to androidx.compose.ui.graphics.Color(0xFF4CAF50),
        )
    }
}
