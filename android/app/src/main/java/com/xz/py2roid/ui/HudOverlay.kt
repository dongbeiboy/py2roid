package com.xz.py2roid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class HudData(
    val fps: Float = 0f,
    val targetCount: Int = 0,
    val provider: String = "CPU",
    val commState: String = "离线",
    val frameTimeMs: Long = 0,
    val cpuLoad: Int = 0,
    val cpuTemp: Int = 0,
    val gpuLoad: Int = 0
)

@Composable
fun HudOverlay(
    hudData: HudData,
    isLandscape: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        val gpuText = if (hudData.gpuLoad < 0) "GPU N/A" else "GPU ${hudData.gpuLoad}%"
        val p = if (isLandscape) 4.dp else 8.dp
        // 横屏时右侧给 ControlBar 留出空间（56dp 栏宽 + 4dp 间距）
        val endPadding = if (isLandscape) 60.dp else p

        // 左上：FPS/推理/耗时/CPU/GPU
        val cpuTempStr = if (hudData.cpuTemp < 0) "N/A" else "${hudData.cpuTemp}℃"
        HudBadge(
            text = "${"%.0f".format(hudData.fps)} FPS | ${hudData.provider} | ${hudData.frameTimeMs}ms\nCPU ${hudData.cpuLoad}% $cpuTempStr | $gpuText",
            color = when {
                hudData.cpuTemp < 0 -> Color.White
                hudData.cpuTemp >= 90 -> Color(0xFFFF5252)
                hudData.cpuTemp >= 75 -> Color(0xFFFFD740)
                else -> Color.White
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = p, top = p)
        )

        // 右上：目标数/通讯（横屏时避开右侧按钮栏）
        HudBadge(
            text = "目标:${hudData.targetCount} ${hudData.commState}",
            color = if (hudData.targetCount > 0) Color(0xFF4CAF50) else Color(0xFF888888),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = endPadding, top = p)
        )
    }
}

@Composable
private fun HudBadge(
    text: String,
    color: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 13.sp,
        modifier = modifier
            .background(Color(0x80000000), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}
