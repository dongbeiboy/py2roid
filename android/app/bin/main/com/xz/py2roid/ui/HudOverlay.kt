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
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // 左上区：FPS / 推理后端 / 帧耗时 / CPU/GPU
        val gpuText = if (hudData.gpuLoad < 0) "GPU N/A" else "GPU ${hudData.gpuLoad}%"
        HudBadge(
            text = "${hudData.fps.toInt()} FPS | ${hudData.provider} | ${hudData.frameTimeMs}ms\nCPU ${hudData.cpuLoad}% ${hudData.cpuTemp}℃ | $gpuText",
            color = when {
                hudData.cpuTemp >= 90 -> Color(0xFFFF5252)
                hudData.cpuTemp >= 75 -> Color(0xFFFFD740)
                else -> Color.White
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        )

        // 右上区：目标数 / 通讯状态
        HudBadge(
            text = "目标: ${hudData.targetCount} | ${hudData.commState}",
            color = if (hudData.targetCount > 0) Color(0xFF4CAF50) else Color(0xFF888888),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
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
