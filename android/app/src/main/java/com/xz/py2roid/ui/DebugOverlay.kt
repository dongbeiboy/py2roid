package com.xz.py2roid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 半透明调试日志覆盖层。
 * 自动滚动显示最新的 logLines，点击穿透。
 */
@Composable
fun DebugOverlay(
    logLines: List<String>,
    modifier: Modifier = Modifier
) {
    if (logLines.isEmpty()) return

    val listState = rememberLazyListState()

    // 新行加入时滚到底部（无动画，防卡死）
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.scrollToItem(logLines.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x80000000)) // 半透明黑底
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            items(logLines) { line ->
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
}
