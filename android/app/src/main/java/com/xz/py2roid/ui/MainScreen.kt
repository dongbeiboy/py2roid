package com.xz.py2roid.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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

    when (screen) {
        AppScreen.Settings -> {
            SettingsScreen(
                settings = settings,
                onConfidenceChange = viewModel::updateConfidence,
                onIouChange = viewModel::updateIou,
                onCommModeChange = viewModel::updateCommMode,
                onBackendChange = viewModel::updateBackend,
                onDebugOverlayChange = viewModel::updateDebugOverlay,
                onBack = viewModel::navigateToMain
            )
        }

        AppScreen.Main -> {
            val context = LocalContext.current
            val previewView = remember { PreviewView(context) }

            LaunchedEffect(previewView) {
                viewModel.setPreviewView(previewView)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                // Layer 2: 摄像头预览（全屏铺满）
                CameraPreview(
                    previewView = previewView,
                    boxes = detectionBoxes,
                    modifier = Modifier.fillMaxSize()
                )

                // Layer 3: HUD 信息层（顶层，点击穿透，占据状态栏位置）
                HudOverlay(
                    hudData = HudData(
                        fps = hudInfo.fps,
                        targetCount = hudInfo.targetCount,
                        provider = hudInfo.provider,
                        commState = hudInfo.commState,
                        frameTimeMs = hudInfo.frameTimeMs
                    ),
                    modifier = Modifier.fillMaxSize()
                )

                // 调试日志覆盖层（开关在设置页）
                if (settings.debugOverlayEnabled) {
                    DebugOverlay(
                        logLines = logLines,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Layer 1: 底部控制栏（导航栏避让，防止手势冲突）
                ControlBar(
                    onSettingsClick = viewModel::navigateToSettings,
                    onModelClick = viewModel::showModelPicker,
                    onCommClick = { /* TODO: Phase 4 通讯模式切换 */ },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                )
            }

            // 模型选择 BottomSheet
            if (showModelPicker) {
                ModelPicker(
                    models = models,
                    selectedModel = selectedModel,
                    onModelSelected = viewModel::selectModel,
                    onImportClick = { /* TODO: Phase 3b 文件选择器 */ },
                    onDismiss = viewModel::hideModelPicker
                )
            }
        }
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
