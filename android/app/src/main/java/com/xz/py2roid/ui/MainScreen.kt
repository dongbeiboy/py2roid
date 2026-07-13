package com.xz.py2roid.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xz.py2roid.bridge.ScriptRunner
import com.xz.py2roid.vision.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    modelManager: ModelManager? = null,
    startImmediately: Boolean = false
) {
    val screen by viewModel.screen.collectAsState()
    val hudInfo by viewModel.hudInfo.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val showModelPicker by viewModel.showModelPicker.collectAsState()
    val showCommPicker by viewModel.showCommPicker.collectAsState()
    val models by viewModel.models.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val detectionBoxes by viewModel.detectionBoxes.collectAsState()
    val logLines by viewModel.logLines.collectAsState()
    val scriptState by viewModel.scriptState.collectAsState()
    val scriptLogLines by viewModel.scriptLogLines.collectAsState()
    val showScriptPicker by viewModel.showScriptPicker.collectAsState()
    val availableScripts by viewModel.availableScripts.collectAsState()
    val selectedScript by viewModel.selectedScript.collectAsState()

    // 全局隐藏状态栏，所有子界面（主界面 + 设置页）共享全屏
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        hideSystemBars(window)
        onDispose { showSystemBars(window) }
    }

    // PreviewView 在 when 外部创建，切页面不销毁
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    LaunchedEffect(previewView) {
        viewModel.setPreviewView(previewView)
    }

    // 文件导入选择器
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || modelManager == null) return@rememberLauncherForActivityResult
        scope.launch {
            val mgr = modelManager ?: return@launch
            val fileName = getFileName(context, uri)
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    mgr.importModel(stream, fileName)
                }
                val scanned = mgr.scanModels().map {
                    ModelItem(name = it.name, inputSize = it.inputSize)
                }
                withContext(Dispatchers.Main) {
                    viewModel.updateModels(scanned)
                }
            }
        }
    }

    // 脚本导入文件选择器
    val scriptImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val fileName = getScriptFileName(context, uri)
            withContext(Dispatchers.IO) {
                val scriptsDir = java.io.File(context.filesDir, "scripts")
                scriptsDir.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val target = java.io.File(scriptsDir, fileName)
                    target.outputStream().use { stream.copyTo(it) }
                }
                scanScripts(context, viewModel)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val isLandscape = maxWidth > maxHeight

        // 启动时按设置决定进入配置页还是直接检测（ADB 模式跳过配置页）
        LaunchedEffect(Unit) {
            if (settings.startOnConfig && !startImmediately) {
                viewModel.navigateToConfig()
            }
        }

        // 相机层始终在 composition 中，切设置页时保持 Surface
        CameraPreview(
            previewView = previewView,
            boxes = detectionBoxes,
            modifier = Modifier.fillMaxSize()
        )

        when (screen) {
            AppScreen.Config -> {
                // 配置页显示系统状态栏
                DisposableEffect(Unit) {
                    val w = (view.context as? android.app.Activity)?.window
                    showSystemBars(w)
                    onDispose { hideSystemBars(w) }
                }
                ConfigScreen(
                    models = models,
                    selectedModel = selectedModel,
                    settings = settings,
                    enabledBackends = viewModel.enabledBackends,
                    onModelSelected = viewModel::selectModel,
                    onBackendChange = viewModel::updateBackend,
                    onStartOnConfigChange = viewModel::updateStartOnConfig,
                    onStart = {
                        viewModel.requestStart()
                        viewModel.navigateToMain()
                    },
                    onOpenSettings = viewModel::navigateToSettings
                )
            }

            AppScreen.Settings -> {
                // 竖屏显示状态栏（便于阅读设置项），横屏全屏沉浸
                DisposableEffect(isLandscape) {
                    val w = (view.context as? android.app.Activity)?.window
                    if (isLandscape) {
                        hideSystemBars(w)
                    } else {
                        showSystemBars(w)
                    }
                    onDispose { hideSystemBars(w) }
                }
                SettingsScreen(
                    settings = settings,
                    enabledBackends = viewModel.enabledBackends,
                    currentProvider = hudInfo.provider,
                    onConfidenceChange = viewModel::updateConfidence,
                    onIouChange = viewModel::updateIou,
                    onCommModeChange = viewModel::updateCommMode,
                    onBackendChange = viewModel::updateBackend,
                    onDebugOverlayChange = viewModel::updateDebugOverlay,
                    onAppModeChange = viewModel::updateAppMode,
                    onBack = viewModel::navigateToMain,
                    onGoConfig = viewModel::navigateToConfig
                )
            }

            AppScreen.Main -> {
                // HUD 信息层
                HudOverlay(
                    hudData = HudData(
                        fps = hudInfo.fps,
                        targetCount = hudInfo.targetCount,
                        provider = hudInfo.provider,
                        commState = hudInfo.commState,
                        frameTimeMs = hudInfo.frameTimeMs,
                        cpuLoad = hudInfo.cpuLoad,
                        cpuTemp = hudInfo.cpuTemp,
                        gpuLoad = hudInfo.gpuLoad
                    ),
                    isLandscape = isLandscape,
                    modifier = Modifier.fillMaxSize()
                )

                // 控制栏（竖屏底部 / 横屏右侧）
                if (isLandscape) {
                    ControlBar(
                        isLandscape = true,
                        appMode = settings.appMode,
                        onSettingsClick = viewModel::navigateToSettings,
                        onModelClick = viewModel::showModelPicker,
                        onCommClick = viewModel::showCommPicker,
                        onScriptClick = viewModel::showScriptPicker,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                    )
                } else {
                    ControlBar(
                        appMode = settings.appMode,
                        onSettingsClick = viewModel::navigateToSettings,
                        onModelClick = viewModel::showModelPicker,
                        onCommClick = viewModel::showCommPicker,
                        onScriptClick = viewModel::showScriptPicker,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                    )
                }

                // OpenMV 模式：脚本控制台 + HUD 状态行
                if (settings.appMode == AppMode.OPENMV) {
                    val scriptLabel = when (scriptState) {
                        ScriptRunner.ScriptState.IDLE -> "空闲"
                        ScriptRunner.ScriptState.RUNNING -> "运行中"
                        ScriptRunner.ScriptState.STOPPED -> "已停止"
                        ScriptRunner.ScriptState.ERROR -> "错误"
                    }
                    // 脚本状态信息（叠加在 HUD 区域）
                    Text(
                        text = "📜 ${selectedScript?.displayName ?: "未选择"} $scriptLabel",
                        fontSize = 10.sp,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier
                            .align(if (isLandscape) Alignment.TopStart else Alignment.TopCenter)
                            .padding(8.dp)
                    )

                    // 脚本控制台（左下角）
                    ScriptConsole(
                        lines = scriptLogLines,
                        isRunning = scriptState == ScriptRunner.ScriptState.RUNNING,
                        onStart = { /* see MainActivity */ },
                        onStop = { /* see MainActivity */ },
                        onRestart = { /* see MainActivity */ },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 4.dp, bottom = if (isLandscape) 4.dp else 56.dp)
                    )
                }

                // 调试日志覆盖层
                if (settings.debugOverlayEnabled) {
                    if (isLandscape) {
                        DebugOverlay(
                            logLines = logLines,
                            isLandscape = true,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 8.dp, bottom = 8.dp)
                        )
                    } else {
                        DebugOverlay(
                            logLines = logLines,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 56.dp)
                        )
                    }
                }
            }
        }
    }

    // 模型选择 BottomSheet（独立于 when，始终可触发）
    if (showModelPicker) {
        ModelPicker(
            models = models,
            selectedModel = selectedModel,
            onModelSelected = viewModel::selectModel,
            onImportClick = { importLauncher.launch(arrayOf("*/*")) },
            onDismiss = viewModel::hideModelPicker
        )
    }

    // 通讯模式选择 BottomSheet
    if (showCommPicker) {
        CommPicker(
            commModes = CommMode.entries,
            selectedMode = settings.commMode,
            onModeSelected = viewModel::updateCommMode,
            onDismiss = viewModel::hideCommPicker
        )
    }

    // 脚本选择 BottomSheet
    if (showScriptPicker) {
        ScriptPicker(
            scripts = availableScripts,
            selectedScript = selectedScript,
            onScriptSelected = { script ->
                viewModel.selectScript(script)
                viewModel.hideScriptPicker()
            },
            onImportClick = { scriptImportLauncher.launch(arrayOf("*/*")) },
            onDeployExamples = {
                scope.launch {
                    deployExampleScripts(context, viewModel)
                }
            },
            onDismiss = viewModel::hideScriptPicker
        )
    }
}

private fun getScriptFileName(context: android.content.Context, uri: android.net.Uri): String {
    var name = "script.py"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}

private fun scanScripts(context: android.content.Context, viewModel: MainViewModel) {
    val scriptsDir = java.io.File(context.filesDir, "scripts")
    if (!scriptsDir.exists()) return
    val files = scriptsDir.listFiles { f -> f.name.endsWith(".py") } ?: emptyArray()
    viewModel.setScripts(files.map { ScriptFile(name = it.name, displayName = it.name) })
}

private fun deployExampleScripts(context: android.content.Context, viewModel: MainViewModel) {
    val scriptsDir = java.io.File(context.filesDir, "scripts")
    scriptsDir.mkdirs()
    val examples = mapOf(
        "01_color_tracking.py" to _EXAMPLE_COLOR_TRACKING,
        "02_apriltag_detection.py" to _EXAMPLE_APRILTAG,
        "03_qrcode_scanner.py" to _EXAMPLE_QRCODE,
        "04_uart_relay.py" to _EXAMPLE_UART_RELAY,
        "05_face_detection.py" to _EXAMPLE_FACE_DETECTION,
    )
    for ((name, content) in examples) {
        val f = java.io.File(scriptsDir, name)
        if (!f.exists()) {
            f.writeText(content)
        }
    }
    scanScripts(context, viewModel)
}

// ── 内置示例脚本 ──

private val _EXAMPLE_COLOR_TRACKING = """
import sensor, image, time
from machine import UART

sensor.reset()
sensor.set_pixformat(sensor.RGB565)
sensor.set_framesize(sensor.QVGA)
sensor.skip_frames(100)

uart = UART(3, 115200)
red_threshold = (30, 100, 15, 127, 15, 127)

while True:
    img = sensor.snapshot()
    blobs = img.find_blobs([red_threshold])
    if blobs:
        for b in blobs:
            img.draw_rectangle(b.rect(), color=(255,0,0))
            img.draw_cross(b.cx(), b.cy(), color=(255,0,0))
            uart.write(f"RED:{b.cx()},{b.cy()},{b.w()},{b.h()}\\n")
    time.sleep_ms(50)
""".trimIndent()

private val _EXAMPLE_APRILTAG = """
import sensor, image, time
from machine import UART

sensor.reset()
sensor.set_pixformat(sensor.RGB565)
sensor.set_framesize(sensor.VGA)
sensor.skip_frames(100)

uart = UART(3, 115200)
while True:
    img = sensor.snapshot()
    tags = img.find_apriltags()
    for tag in tags:
        img.draw_rectangle(tag.rect(), color=(0,255,0))
        img.draw_string(tag.cx(), tag.cy(), f"ID:{tag.id()}", color=(0,255,0))
        uart.write(f"TAG:{tag.id()},{tag.cx()},{tag.cy()}\\n")
""".trimIndent()

private val _EXAMPLE_QRCODE = """
import sensor, image, time
from machine import UART

sensor.reset()
sensor.set_pixformat(sensor.GRAYSCALE)
sensor.set_framesize(sensor.VGA)
sensor.skip_frames(100)

uart = UART(3, 115200)
while True:
    img = sensor.snapshot()
    codes = img.find_qrcodes()
    for code in codes:
        img.draw_rectangle(code.rect(), color=(255,255,255))
        img.draw_string(code.cx(), code.cy(), code.payload()[:20])
        uart.write(f"QR:{code.payload()}\\n")
    time.sleep_ms(100)
""".trimIndent()

private val _EXAMPLE_UART_RELAY = """
import sensor, time
from machine import UART

sensor.reset()
sensor.set_pixformat(sensor.RGB565)
sensor.set_framesize(sensor.QVGA)

uart = UART(3, 115200)
counter = 0
while True:
    uart.write(f"HEARTBEAT:{counter}\\n")
    counter += 1
    time.sleep_ms(1000)
""".trimIndent()

private val _EXAMPLE_FACE_DETECTION = """
import sensor, image, time
from machine import UART

sensor.reset()
sensor.set_pixformat(sensor.GRAYSCALE)
sensor.set_framesize(sensor.QVGA)
sensor.skip_frames(100)

face_cascade = image.HaarCascade("frontalface")
uart = UART(3, 115200)

while True:
    img = sensor.snapshot()
    faces = img.find_features(face_cascade)
    for f in faces:
        img.draw_rectangle(f.rect(), color=(255,255,255))
        uart.write(f"FACE:{f.x()},{f.y()},{f.w()},{f.h()}\\n")
    if not faces:
        uart.write("NO_FACE\\n")
""".trimIndent()

private fun getFileName(context: android.content.Context, uri: android.net.Uri): String {
    var name = "model.onnx"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}

private fun hideSystemBars(window: android.view.Window?) {
    if (window == null) return
    val wic = WindowInsetsControllerCompat(window, window.decorView)
    wic.hide(WindowInsetsCompat.Type.systemBars())
    wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}

private fun showSystemBars(window: android.view.Window?) {
    if (window == null) return
    WindowInsetsControllerCompat(window, window.decorView)
        .show(WindowInsetsCompat.Type.systemBars())
}
