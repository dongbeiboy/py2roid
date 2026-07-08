package com.xz.py2roid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 半透明调试日志覆盖层 — 底部区域，不遮挡摄像头主体画面。
 */
@Composable
fun DebugOverlay(
    logLines: List<String>,
    modifier: Modifier = Modifier
) {
    if (logLines.isEmpty()) return

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(Color(0x88000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .verticalScroll(scrollState)
    ) {
        logLines.forEach { line ->
            val color = when {
                line.startsWith("[E]") -> Color(0xFFFF5252)
                line.startsWith("[W]") -> Color(0xFFFFD740)
                line.startsWith("[I]") -> Color(0xFF69F0AE)
                else -> Color(0xFFB0BEC5)
            }
            Text(
                text = line,
                color = color,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 0.5.dp)
            )
        }
    }
}
