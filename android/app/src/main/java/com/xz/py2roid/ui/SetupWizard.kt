package com.xz.py2roid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SetupWizard(
    isVivoDevice: Boolean = false,
    onComplete: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    val steps = buildList {
        add("欢迎" to "py2roid\nAndroid 视觉识别工具\n\n手机做视觉，单片机做控制。")
        add("权限" to "需要以下权限:\n• 摄像头 — 实时检测\n• 通知 — 后台服务")
        if (isVivoDevice) {
            add("OriginOS 设置" to "vivo 设备需手动配置:\n• 后台高功耗 → 允许\n• 自启动 → 允许")
        }
        add("完成" to "设置完成!\n点击开始使用")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val (title, content) = steps[step]

        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                if (step < steps.lastIndex) step++
                else onComplete()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (step < steps.lastIndex) "下一步" else "开始使用")
        }
    }
}
