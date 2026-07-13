package com.xz.py2roid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ControlBar(
    isLandscape: Boolean = false,
    appMode: AppMode = AppMode.LEGACY,
    onSettingsClick: () -> Unit = {},
    onModelClick: () -> Unit = {},
    onCommClick: () -> Unit = {},
    onScriptClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isOpenmv = appMode == AppMode.OPENMV

    if (isLandscape) {
        // 横屏：右侧竖排单字按钮
        Column(
            modifier = modifier
                .fillMaxHeight()
                .width(44.dp)
                .background(Color(0xCC000000))
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            Button(onClick = onSettingsClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White), contentPadding = PaddingValues(2.dp), modifier = Modifier.fillMaxWidth().weight(1f)) { Text("设\n置", fontSize = 12.sp) }
            if (!isOpenmv) {
                Button(onClick = onModelClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White), contentPadding = PaddingValues(2.dp), modifier = Modifier.fillMaxWidth().weight(1f)) { Text("模\n型", fontSize = 12.sp) }
                Button(onClick = onCommClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White), contentPadding = PaddingValues(2.dp), modifier = Modifier.fillMaxWidth().weight(1f)) { Text("通\n讯", fontSize = 12.sp) }
            } else {
                Button(onClick = onScriptClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White), contentPadding = PaddingValues(2.dp), modifier = Modifier.fillMaxWidth().weight(1f)) { Text("脚\n本", fontSize = 12.sp) }
                Button(onClick = onCommClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White), contentPadding = PaddingValues(2.dp), modifier = Modifier.fillMaxWidth().weight(1f)) { Text("通\n讯", fontSize = 12.sp) }
            }
        }
    } else {
        // 竖屏：底部横排
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .height(48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onSettingsClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White), modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) { Text("设置", fontSize = 13.sp) }
            if (!isOpenmv) {
                Button(onClick = onModelClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White), modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) { Text("模型", fontSize = 13.sp) }
                Button(onClick = onCommClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White), modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) { Text("通讯", fontSize = 13.sp) }
            } else {
                Button(onClick = onScriptClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White), modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) { Text("📜脚本", fontSize = 13.sp) }
                Button(onClick = onCommClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White), modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) { Text("通讯", fontSize = 13.sp) }
            }
        }
    }
}
