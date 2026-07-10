package com.xz.py2roid.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel()
) {
    val screen by viewModel.screen.collectAsState()
    val hudInfo by viewModel.hudInfo.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val showModelPicker by viewModel.showModelPicker.collectAsState()
    val models by viewModel.models.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val detectionBoxes by viewModel.detectionBoxes.collectAsState()
    val logLines by viewModel.logLines.collectAsState()

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

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val isLandscape = maxWidth > maxHeight

        // 相机层始终在 composition 中，切设置页时保持 Surface
        CameraPreview(
            previewView = previewView,
            boxes = detectionBoxes,
            modifier = Modifier.fillMaxSize()
        )

        when (screen) {
            AppScreen.Settings -> {
                SettingsScreen(
                    settings = settings,
                    enabledBackends = viewModel.enabledBackends,
                    onConfidenceChange = viewModel::updateConfidence,
                    onIouChange = viewModel::updateIou,
                    onCommModeChange = viewModel::updateCommMode,
                    onBackendChange = viewModel::updateBackend,
                    onDebugOverlayChange = viewModel::updateDebugOverlay,
                    onBack = viewModel::navigateToMain
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
                        onSettingsClick = viewModel::navigateToSettings,
                        onModelClick = viewModel::showModelPicker,
                        onCommClick = { },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                    )
                } else {
                    ControlBar(
                        onSettingsClick = viewModel::navigateToSettings,
                        onModelClick = viewModel::showModelPicker,
                        onCommClick = { },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
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
            onImportClick = { },
            onDismiss = viewModel::hideModelPicker
        )
    }
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
