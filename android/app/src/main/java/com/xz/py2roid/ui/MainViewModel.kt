package com.xz.py2roid.ui

import androidx.camera.view.PreviewView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xz.py2roid.util.Logger
import com.xz.py2roid.vision.TfliteEngine
import com.xz.py2roid.vision.VcapEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HudInfo(
    val fps: Float = 0f,
    val targetCount: Int = 0,
    val provider: String = "CPU",
    val frameTimeMs: Long = 0,
    val commState: String = "离线",
    val cpuLoad: Int = 0,
    val cpuTemp: Int = 0,
    val gpuLoad: Int = 0
)

enum class AppScreen { Main, Settings, Config }

class MainViewModel : ViewModel() {

    companion object {
        private const val MAX_LOG_LINES = 200
    }

    init {
        viewModelScope.launch {
            Logger.logFlow.collect { line -> addLogLine(line) }
        }
    }

    private val _screen = MutableStateFlow(AppScreen.Main)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _hudInfo = MutableStateFlow(HudInfo())
    val hudInfo: StateFlow<HudInfo> = _hudInfo.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _showModelPicker = MutableStateFlow(false)
    val showModelPicker: StateFlow<Boolean> = _showModelPicker.asStateFlow()

    private val _showCommPicker = MutableStateFlow(false)
    val showCommPicker: StateFlow<Boolean> = _showCommPicker.asStateFlow()

    private val _models = MutableStateFlow<List<ModelItem>>(
        listOf(ModelItem(name = "yolov8n.onnx", inputSize = "640x640", classes = 80))
    )
    val models: StateFlow<List<ModelItem>> = _models.asStateFlow()

    private val _selectedModel = MutableStateFlow("last.onnx")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    // PreviewView 引用
    private val _previewView = MutableStateFlow<PreviewView?>(null)
    val previewView: StateFlow<PreviewView?> = _previewView.asStateFlow()

    // 检测框列表
    private val _detectionBoxes = MutableStateFlow<List<BoundingBox>>(emptyList())
    val detectionBoxes: StateFlow<List<BoundingBox>> = _detectionBoxes.asStateFlow()

    // 相机权限已授予
    private val _cameraPermissionGranted = MutableStateFlow(false)
    val cameraPermissionGranted: StateFlow<Boolean> = _cameraPermissionGranted.asStateFlow()

    // 模型文件已就绪
    private val _modelsReady = MutableStateFlow(false)
    val modelsReady: StateFlow<Boolean> = _modelsReady.asStateFlow()

    // 检测运行状态
    private val _isDetecting = MutableStateFlow(false)
    val isDetecting: StateFlow<Boolean> = _isDetecting.asStateFlow()

    // 启动检测触发信号（由 ConfigScreen "开始检测" / ADB / 直启模式发出）
    private val _startRequested = MutableStateFlow(false)
    val startRequested: StateFlow<Boolean> = _startRequested.asStateFlow()

    fun requestStart() { _startRequested.value = true }

    // Navigation
    fun navigateToConfig() { _screen.value = AppScreen.Config }
    fun navigateToMain() { _screen.value = AppScreen.Main }
    fun navigateToSettings() { _screen.value = AppScreen.Settings }

    // Model picker
    fun showModelPicker() { _showModelPicker.value = true }
    fun hideModelPicker() { _showModelPicker.value = false }
    fun selectModel(name: String) { _selectedModel.value = name }
    fun updateModels(items: List<ModelItem>) { _models.value = items }

    // Comm picker
    fun showCommPicker() { _showCommPicker.value = true }
    fun hideCommPicker() { _showCommPicker.value = false }

    // Settings
    fun updateConfidence(value: Float) {
        _settings.value = _settings.value.copy(confidenceThreshold = value)
    }
    fun updateIou(value: Float) {
        _settings.value = _settings.value.copy(iouThreshold = value)
    }
    fun updateCommMode(mode: CommMode) {
        _settings.value = _settings.value.copy(commMode = mode)
    }
    fun updateBackend(backend: InferenceBackend) {
        _settings.value = _settings.value.copy(inferenceBackend = backend)
    }
    fun updateStartOnConfig(value: Boolean) {
        _settings.value = _settings.value.copy(startOnConfig = value)
    }

    // 可用后端集合（启动时检测一次）
    val enabledBackends: Set<InferenceBackend> = buildSet {
        add(InferenceBackend.Auto)
        add(InferenceBackend.CPU)
        add(InferenceBackend.XNNPACK)
        add(InferenceBackend.NNAPI)
        if (VcapEngine.isSdkAvailable()) add(InferenceBackend.VCAP)
        add(InferenceBackend.TFLITE)
        if (TfliteEngine.isGpuAvailable()) add(InferenceBackend.TFLITE_GPU)
        add(InferenceBackend.TFLITE_NNAPI)
    }

    fun updateDebugOverlay(enabled: Boolean) {
        _settings.value = _settings.value.copy(debugOverlayEnabled = enabled)
    }

    // HUD (called from Detector)
    fun updateHud(info: HudInfo) {
        _hudInfo.value = info
    }

    // PreviewView
    fun setPreviewView(view: PreviewView) { _previewView.value = view }

    // Camera permission granted (called from permission callback)
    fun onCameraPermissionGranted() { _cameraPermissionGranted.value = true }

    // Models ready (called after initModels completes)
    fun onModelsReady() { _modelsReady.value = true }

    // Detection boxes
    fun updateBoxes(boxes: List<BoundingBox>) { _detectionBoxes.value = boxes }

    // Detection state
    fun setDetecting(value: Boolean) { _isDetecting.value = value }

    // Debug overlay — 限速：最多每 100ms 写入一次
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()
    private var lastLogTime = 0L

    fun addLogLine(line: String) {
        val now = System.currentTimeMillis()
        if (now - lastLogTime < 100) return // 限速 10 条/秒
        lastLogTime = now
        val current = _logLines.value
        val updated = if (current.size >= MAX_LOG_LINES) {
            current.drop(current.size - MAX_LOG_LINES + 1) + line
        } else {
            current + line
        }
        _logLines.value = updated
    }

    fun clearLogLines() {
        _logLines.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        // Flow 收集随 viewModelScope 自动取消，无需手动清理
    }
}
