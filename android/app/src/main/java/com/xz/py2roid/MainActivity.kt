package com.xz.py2roid

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import android.content.pm.ApplicationInfo
import com.xz.py2roid.bridge.PythonBridge
import com.xz.py2roid.serial.UsbSerialManager
import com.xz.py2roid.server.WebServerManager
import com.xz.py2roid.service.DetectionForegroundService
import com.xz.py2roid.ui.AppScreen
import com.xz.py2roid.ui.BoundingBox
import com.xz.py2roid.ui.CommMode
import com.xz.py2roid.ui.HudInfo
import com.xz.py2roid.ui.InferenceBackend
import com.xz.py2roid.ui.MainScreen
import com.xz.py2roid.ui.MainViewModel
import com.xz.py2roid.ui.Py2roidTheme
import com.xz.py2roid.util.Logger
import com.xz.py2roid.util.OriginOsHelper
import com.xz.py2roid.util.PermissionHelper
import com.xz.py2roid.util.SettingsStore
import com.xz.py2roid.util.SystemStats
import com.xz.py2roid.vision.CameraController
import com.xz.py2roid.vision.Detector
import com.xz.py2roid.vision.ImagePreprocessor
import com.xz.py2roid.vision.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var modelManager: ModelManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var viewModel: MainViewModel
    private var cameraController: CameraController? = null
    private var detector: Detector? = null
    private var isRunning = false
    private var latestTargetCount = 0
    private var lastNotificationTime = 0L
    // [DEBUG] ADB intent extra 覆写（仅 debug 包有效）
    private var adbModelOverride: String? = null
    private var adbBackendOverride: InferenceBackend? = null

    /** ADB 传参时跳过配置页直接检测 */
    private val adbActive get() = adbModelOverride != null || adbBackendOverride != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        modelManager = ModelManager(this)
        settingsStore = SettingsStore(this)
        permissionHelper = PermissionHelper(this)
        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)

        // [DEBUG] ADB intent extra 覆写（仅 debug 包有效，存字段不走 prefs 避免 LaunchedEffect 覆盖）
        // adb shell am start -n com.xz.py2roid/.MainActivity \
        //   --es model yolov8n.tflite --es backend TFLITE
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent?.extras?.let { extras ->
                extras.getString("model")?.let {
                    adbModelOverride = it
                    Logger.i("[ADB] model override: $it")
                }
                extras.getString("backend")?.let {
                    try {
                        adbBackendOverride = InferenceBackend.valueOf(it)
                        Logger.i("[ADB] backend override: $it")
                    } catch (_: IllegalArgumentException) {
                        Logger.w("[ADB] unknown backend: $it")
                    }
                }
            }
        }

        // 解压内置模型
        lifecycleScope.launch(Dispatchers.IO) {
            val models = modelManager.initModels()
            Logger.d("Models initialized: ${models.size} models")
            withContext(Dispatchers.Main) {
                viewModel.updateModels(models.map {
                    com.xz.py2roid.ui.ModelItem(name = it.name, inputSize = it.inputSize)
                })
                viewModel.onModelsReady()
            }
        }

        // 初始化 Python 桥接
        lifecycleScope.launch(Dispatchers.IO) {
            PythonBridge.init()
        }

        setContent {
            val previewView by viewModel.previewView.collectAsState()
            val screen by viewModel.screen.collectAsState()
            val cameraPermissionGranted by viewModel.cameraPermissionGranted.collectAsState()
            val modelsReady by viewModel.modelsReady.collectAsState()
            val startRequested by viewModel.startRequested.collectAsState()
            val currentSettings by viewModel.settings.collectAsState()

            // ── 启动检测 ──
            // startRequested 由 ConfigScreen "开始检测" / ADB 模式 / 直启模式发出，
            // 等所有条件就绪后执行 startDetection()
            LaunchedEffect(startRequested, previewView, cameraPermissionGranted, modelsReady) {
                if (startRequested && previewView != null && cameraPermissionGranted && modelsReady && !isRunning) {
                    isRunning = true
                    startDetection(viewModel, previewView!!)
                }
            }

            // 自动触发 startRequested：
            //   - ADB 调试模式 → 直接跑
            //   - startOnConfig=false → 用户选择了直启，不等手动
            LaunchedEffect(Unit) {
                if (adbActive || !currentSettings.startOnConfig) {
                    viewModel.requestStart()
                }
            }

            // 系统返回键/手势：设置页/配置页 → 主界面，主界面才退出
            BackHandler(screen == AppScreen.Settings || screen == AppScreen.Config) {
                viewModel.navigateToMain()
            }

            // 从设置页返回主界面时，重启相机（设置页遮挡可能丢失 Surface）
            LaunchedEffect(screen) {
                if (screen == AppScreen.Main && isRunning) {
                    delay(100)
                    cameraController?.startCamera()
                }
            }

            // 实时同步设置 → Detector（阈值滑块即时生效）
            val selectedModelName by viewModel.selectedModel.collectAsState()
            val prevBackend = remember { mutableStateOf(currentSettings.inferenceBackend) }
            val prevModel = remember { mutableStateOf(selectedModelName) }

            // 持久化设置 + 后端变更 → 重载模型
            LaunchedEffect(currentSettings) {
                settingsStore.save(currentSettings)
                detector?.let { d ->
                    d.confidenceThreshold = currentSettings.confidenceThreshold
                    d.iouThreshold = currentSettings.iouThreshold
                    if (currentSettings.inferenceBackend != prevBackend.value) {
                        prevBackend.value = currentSettings.inferenceBackend
                        val models = modelManager.scanModels()
                        viewModel.updateModels(models.map { com.xz.py2roid.ui.ModelItem(name = it.name, inputSize = it.inputSize) })
                        val modelPath = models.find { it.name == selectedModelName }?.path
                        if (modelPath != null) {
                            Logger.i("Reloading model: $selectedModelName backend=${currentSettings.inferenceBackend}")
                            launch(Dispatchers.IO) {
                                try {
                                    d.loadModel(modelPath, currentSettings.inferenceBackend)
                                } catch (e: Exception) {
                                    Logger.e("Failed to reload model", e)
                                }
                            }
                        }
                    }
                }
            }

            // 持久化模型选择 + 模型变更 → 重载模型
            LaunchedEffect(selectedModelName) {
                settingsStore.setSelectedModel(selectedModelName)
                detector?.let { d ->
                    if (selectedModelName != prevModel.value) {
                        prevModel.value = selectedModelName
                        val models = modelManager.scanModels()
                        val modelPath = models.find { it.name == selectedModelName }?.path
                        if (modelPath != null) {
                            Logger.i("Reloading model: $selectedModelName backend=${currentSettings.inferenceBackend}")
                            launch(Dispatchers.IO) {
                                try {
                                    d.loadModel(modelPath, currentSettings.inferenceBackend)
                                } catch (e: Exception) {
                                    Logger.e("Failed to reload model", e)
                                }
                            }
                        }
                    }
                }
            }

            Py2roidTheme {
                MainScreen(viewModel = viewModel, modelManager = modelManager, startImmediately = adbActive)
            }
        }

        // 权限请求（触发点）
        permissionHelper.requestCamera {
            Logger.i("Camera permission granted, ready to start")
            viewModel.onCameraPermissionGranted()
        }

        // OriginOS 检测
        if (OriginOsHelper.isOriginOs()) {
            Logger.d("OriginOS detected")
        }
    }

    private fun startDetection(viewModel: MainViewModel, previewView: androidx.camera.view.PreviewView) {
        val settings = settingsStore.load()
        val scope = lifecycleScope

        // ── 启动通讯基础设施 ──
        when (settings.commMode) {
            CommMode.USB -> {
                UsbSerialManager.start(this)
                Logger.i("USB serial manager started (mode=${settings.commMode})")
            }
            CommMode.WiFi -> {
                WebServerManager.start()
                Logger.i("WebSocket server started (mode=${settings.commMode})")
            }
            CommMode.Off -> { /* 不启动通讯 */ }
        }

        // 注册 PythonBridge 回调：USB/WS 发送与配置读取
        PythonBridge.registerCallback("send_to_usb") { data: Any ->
            if (data is ByteArray) {
                scope.launch { UsbSerialManager.write(data) }
            }
        }
        PythonBridge.registerCallback("send_to_ws") { data: Any ->
            if (data is ByteArray) {
                WebServerManager.broadcast(data)
            }
        }
        PythonBridge.registerCallback("config_request") {
            settingsStore.load().let { s ->
                java.util.Map.of("confidence", s.confidenceThreshold.toDouble(),
                    "iou", s.iouThreshold.toDouble(),
                    "commMode", s.commMode.name)
            }
        }

        // USB/WS 状态流 → HUD commState
        scope.launch {
            combine(
                UsbSerialManager.connectionState,
                WebServerManager.connectionState
            ) { usb, ws ->
                when {
                    usb is UsbSerialManager.ConnectionState.Connected -> "USB:${(usb.driver)}"
                    ws is WebServerManager.WebSocketState.Running ->
                        "WS:${WebServerManager.clientCount.value}在线"
                    usb is UsbSerialManager.ConnectionState.Error -> "USB:${usb.message}"
                    ws is WebServerManager.WebSocketState.Error -> "WS:${ws.message}"
                    else -> "离线"
                }
            }.collect { state ->
                viewModel.updateHud(viewModel.hudInfo.value.copy(commState = state))
            }
        }

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
                        color = labelColors[r.label] ?: androidx.compose.ui.graphics.Color(0xFF2196F3),
                        frameAspect = r.frameAspect
                    )
                }
                viewModel.updateBoxes(boxes)
                latestTargetCount = results.size

                // 检测结果路由：编码并通过 USB/WS 发送
                if (results.isNotEmpty() && settings.commMode != CommMode.Off) {
                    scope.launch {
                        val frame = PythonBridge.encodeDetection(results)
                        if (frame != null) {
                            when (settings.commMode) {
                                CommMode.USB -> UsbSerialManager.write(frame)
                                CommMode.WiFi -> WebServerManager.broadcast(frame)
                                CommMode.Off -> {}
                            }
                        }
                    }
                }
            },
            onPerformanceUpdate = { fps, provider, frameTimeMs ->
                val commLabel = when {
                    UsbSerialManager.isConnected() -> "USB"
                    WebServerManager.isRunning() -> "WS:${WebServerManager.clientCount.value}"
                    else -> "离线"
                }
                viewModel.updateHud(viewModel.hudInfo.value.copy(
                    fps = fps,
                    targetCount = latestTargetCount,
                    provider = provider,
                    frameTimeMs = frameTimeMs,
                    commState = commLabel
                ))
                // 每 5 秒更新一次前台通知
                val now = System.currentTimeMillis()
                if (now - lastNotificationTime > 5000) {
                    lastNotificationTime = now
                    updateServiceNotification("FPS:${"%.1f".format(fps)} 目标:$latestTargetCount $provider")
                }
            }
        )
        detector = det

        // 加载模型（ADB override 优先于 settingsStore）
        try {
            val modelName = adbModelOverride ?: settingsStore.getSelectedModel()
            val backend = adbBackendOverride ?: settings.inferenceBackend
            val models = modelManager.scanModels()
            viewModel.updateModels(models.map { com.xz.py2roid.ui.ModelItem(name = it.name, inputSize = it.inputSize) })
            val modelPath = models.find { it.name == modelName }?.path
            if (modelPath != null) {
                det.confidenceThreshold = settings.confidenceThreshold
                det.iouThreshold = settings.iouThreshold
                Logger.i("[Device] ${Build.MANUFACTURER} ${Build.MODEL} SDK=${Build.VERSION.SDK_INT}, " +
                        "backend=$backend, conf=${settings.confidenceThreshold}" +
                        if (adbModelOverride != null) " (adb: model=$modelName backend=$backend)" else "")
                det.loadModel(modelPath, backend)
                Logger.i("Model loaded: $modelName")
            } else {
                Logger.w("Model not found: $modelName")
                return
            }
        } catch (e: Exception) {
            val modelName = settingsStore.getSelectedModel()
            val file = java.io.File(modelManager.scanModels().find { it.name == modelName }?.path ?: "?")
            val fileInfo = if (file.exists()) "${file.length()}B" else "not_found"
            Logger.e("[LoadModel] ${e::class.simpleName}: ${e.message} model=$modelName backend=${settings.inferenceBackend} fileSize=$fileInfo")
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
                    val frameStart = System.currentTimeMillis()
                    val imgW = imageProxy.width
                    val imgH = imageProxy.height
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    try {
                        // 一次性提取 NV21，共享给帧缓存和预处理
                        val nv21 = ImagePreprocessor.nv21FromYuv420(image)
                        PythonBridge.updateFrame(nv21, imgW, imgH, 3/*NV21*/, rotation)

                        val preResult = ImagePreprocessor.preprocess(nv21, imgW, imgH, rotation)
                        val preMs = System.currentTimeMillis() - frameStart
                        val results = det.detect(preResult)
                        val totalMs = System.currentTimeMillis() - frameStart
                        if (results.isNotEmpty()) {
                            val topConf = results.maxOf { it.confidence }
                            Logger.i("[Perf] ${imgW}x${imgH} pre=${preMs}ms inf=${totalMs-preMs}ms total=${totalMs}ms det=${results.size} top=${String.format("%.2f", topConf)}")
                        } else if (totalMs > 50) {
                            Logger.d("[Perf] ${imgW}x${imgH} pre=${preMs}ms inf=${totalMs-preMs}ms total=${totalMs}ms det=0")
                        }
                    } catch (e: Exception) {
                        Logger.e("[M01] Frame error: ${e::class.simpleName}: ${e.message} provider=${det.currentProvider}")
                        if (Logger.enabled) {
                            val sw = java.io.StringWriter()
                            val pw = java.io.PrintWriter(sw)
                            e.printStackTrace(pw)
                            val trace = sw.toString().split("\n").take(6).joinToString(" | ")
                            android.util.Log.w("py2roid", "[M01] stack(top6): $trace")
                        }
                    } finally {
                        imageProxy.close()
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

        // 定时轮询系统状态（CPU/GPU/温度），每 3 秒一次
        lifecycleScope.launch(Dispatchers.IO) {
            // 预热，先跑两次建立 CPU 基线
            SystemStats.cpuLoad()
            kotlinx.coroutines.delay(500)
            SystemStats.cpuLoad()

            while (true) {
                kotlinx.coroutines.delay(3000)
                val cpu = SystemStats.cpuLoad()
                val temp = SystemStats.cpuTemp(this@MainActivity)
                val gpu = SystemStats.gpuLoad(this@MainActivity)
                Logger.i("[Stats] CPU=${cpu}% TEMP=${temp}℃ GPU=${gpu}")
                viewModel.updateHud(viewModel.hudInfo.value.copy(
                    cpuLoad = cpu, cpuTemp = temp, gpuLoad = gpu
                ))
            }
        }
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
        if (isRunning) {
            Logger.d("Resuming camera after screen unlock")
            cameraController?.startCamera()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges 阻止了 Activity 重建，需手动重启相机以适配新方向
        if (isRunning) {
            Logger.d("Configuration changed, restarting camera")
            lifecycleScope.launch(Dispatchers.IO) {
                cameraController?.stopCamera()
                kotlinx.coroutines.delay(300)
                cameraController?.startCamera()
            }
        }
    }

    /** 直接更新前台服务通知内容 */
    private fun updateServiceNotification(text: String) {
        try {
            val notification = androidx.core.app.NotificationCompat.Builder(this, "py2roid_detection")
                .setContentTitle("py2roid 检测中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .build()
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.notify(1001, notification)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraController?.destroy()
        UsbSerialManager.stop()
        WebServerManager.stop()
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
