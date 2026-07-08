package com.xz.py2roid.ui

import androidx.camera.view.PreviewView
import androidx.lifecycle.ViewModel
import com.xz.py2roid.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HudInfo(
    val fps: Float = 0f,
    val targetCount: Int = 0,
    val provider: String = "CPU",
    val frameTimeMs: Long = 0,
    val commState: String = "离线"
)

enum class AppScreen { Main, Settings }

class MainViewModel : ViewModel() {

    companion object {
        private const val MAX_LOG_LINES = 200
    }

    init {
        Logger.onLog = { line -> addLogLine(line) }
    }

    private val _screen = MutableStateFlow(AppScreen.Main)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _hudInfo = MutableStateFlow(HudInfo())
    val hudInfo: StateFlow<HudInfo> = _hudInfo.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _showModelPicker = MutableStateFlow(false)
    val showModelPicker: StateFlow<Boolean> = _showModelPicker.asStateFlow()

    private val _models = MutableStateFlow<List<ModelItem>>(
        listOf(ModelItem(name = "yolov8n.onnx", inputSize = "640x640", classes = 80))
    )
    val models: StateFlow<List<ModelItem>> = _models.asStateFlow()

    private val _selectedModel = MutableStateFlow("yolov8n.onnx")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    // PreviewView 引用
    private val _previewView = MutableStateFlow<PreviewView?>(null)
    val previewView: StateFlow<PreviewView?> = _previewView.asStateFlow()

    // 检测框列表
    private val _detectionBoxes = MutableStateFlow<List<BoundingBox>>(emptyList())
    val detectionBoxes: StateFlow<List<BoundingBox>> = _detectionBoxes.asStateFlow()

    // 检测运行状态
    private val _isDetecting = MutableStateFlow(false)
    val isDetecting: StateFlow<Boolean> = _isDetecting.asStateFlow()

    // Navigation
    fun navigateToSettings() { _screen.value = AppScreen.Settings }
    fun navigateToMain() { _screen.value = AppScreen.Main }

    // Model picker
    fun showModelPicker() { _showModelPicker.value = true }
    fun hideModelPicker() { _showModelPicker.value = false }
    fun selectModel(name: String) { _selectedModel.value = name }

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
    fun updateDebugOverlay(enabled: Boolean) {
        _settings.value = _settings.value.copy(debugOverlayEnabled = enabled)
    }

    // HUD (called from Detector)
    fun updateHud(info: HudInfo) {
        _hudInfo.value = info
    }

    // PreviewView
    fun setPreviewView(view: PreviewView) { _previewView.value = view }

    // Detection boxes
    fun updateBoxes(boxes: List<BoundingBox>) { _detectionBoxes.value = boxes }

    // Detection state
    fun setDetecting(value: Boolean) { _isDetecting.value = value }

    // Debug overlay
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    fun addLogLine(line: String) {
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
        Logger.onLog = null
    }
}
