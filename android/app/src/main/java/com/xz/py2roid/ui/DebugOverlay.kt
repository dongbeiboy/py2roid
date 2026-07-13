package com.xz.py2roid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 *
 * @param levelFilter 允许显示的日志级别集合，如 setOf("E", "W", "I")
 * @param hiddenCategories 隐藏的日志来源类别，如 setOf("ADB", "Route")
 */
@Composable
fun DebugOverlay(
    logLines: List<String>,
    levelFilter: Set<String> = setOf("E", "W", "I"),
    hiddenCategories: Set<String> = emptySet(),
    isLandscape: Boolean = false,
    modifier: Modifier = Modifier
) {
    val filtered = logLines.filter { line ->
        // 级别过滤
        val levelMatch = levelFilter.any { line.startsWith("[$it]") }
        if (!levelMatch) return@filter false
        // 类别过滤 — 从行中提取类别，如果在隐藏列表中则过滤掉
        val cat = com.xz.py2roid.util.Logger.extractLogCategory(line)
        cat == null || cat !in hiddenCategories
    }
    if (filtered.isEmpty()) return

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
        }
    }

    Column(
        modifier = modifier
            .then(if (isLandscape) Modifier.width(280.dp) else Modifier.fillMaxWidth())
            .height(120.dp)
            .background(Color(0x88000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .verticalScroll(scrollState)
    ) {
        filtered.forEach { line ->
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


