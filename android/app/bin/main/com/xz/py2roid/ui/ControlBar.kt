package com.xz.py2roid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
    onSettingsClick: () -> Unit = {},
    onModelClick: () -> Unit = {},
    onCommClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .navigationBarsPadding()
            .height(48.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onSettingsClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
        ) {
            Text("设置", fontSize = 13.sp)
        }
        Button(
            onClick = onModelClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
        ) {
            Text("模型", fontSize = 13.sp)
        }
        Button(
            onClick = onCommClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
        ) {
            Text("通讯", fontSize = 13.sp)
        }
    }
}
