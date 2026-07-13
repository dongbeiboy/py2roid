package com.xz.py2roid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 脚本输出控制台。
 *
 * 显示脚本 print() 输出，底部有运行/停止/重启控制按钮。
 * 半透明覆盖层，叠加在预览画面上。
 */
@Composable
fun ScriptConsole(
    lines: List<String>,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // 自动滚动到底部
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Column(
        modifier = modifier
            .width(280.dp)
            .height(200.dp)
            .padding(4.dp)
    ) {
        // 标题
        Text(
            "📜 脚本控制台",
            fontSize = 11.sp,
            color = Color(0xFF4CAF50),
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(4.dp))

        // 输出区
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xCC000000))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            if (lines.isEmpty()) {
                item {
                    Text(
                        "暂无输出…",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }
            } else {
                items(lines.takeLast(100)) { line ->
                    Text(
                        line,
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        lineHeight = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // 控制按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isRunning) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828)
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) { Text("⏹", fontSize = 13.sp) }

                Button(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE65100)
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) { Text("🔄", fontSize = 13.sp) }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32)
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) { Text("▶️ 运行", fontSize = 12.sp) }
            }
        }
    }
}
