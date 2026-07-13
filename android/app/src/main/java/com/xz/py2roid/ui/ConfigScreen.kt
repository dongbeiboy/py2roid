package com.xz.py2roid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConfigScreen(
    models: List<ModelItem>,
    selectedModel: String,
    settings: AppSettings,
    enabledBackends: Set<InferenceBackend>,
    onModelSelected: (String) -> Unit,
    onBackendChange: (InferenceBackend) -> Unit,
    onAppModeChange: (AppMode) -> Unit = {},
    onStartOnConfigChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // 模型切换时，若当前后端不兼容则回退到 Auto
    val modelBackends = remember(selectedModel, enabledBackends) {
        InferenceBackend.backendsForModel(selectedModel, enabledBackends)
    }
    LaunchedEffect(selectedModel) {
        if (settings.inferenceBackend !in modelBackends) {
            onBackendChange(InferenceBackend.Auto)
        }
    }

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("预配置", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text("更多设置 →", color = Color(0xFF4CAF50), fontSize = 14.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 运行模式
            Text("运行模式", color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Surface(
                color = Color(0xFF2A2A2A),
                shape = MaterialTheme.shapes.small
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AppMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = settings.appMode == m,
                                onClick = { onAppModeChange(m) },
                                shape = SegmentedButtonDefaults.itemShape(i, AppMode.entries.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primary,
                                    inactiveContainerColor = Color(0xFF3A3A3A)
                                )
                            ) { Text(m.label, fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (settings.appMode == AppMode.LEGACY) "Kotlin YOLO 检测管线" else "运行 MicroPython 兼容脚本",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            if (settings.appMode == AppMode.LEGACY) {
                // 模型选择
                Text("模型", color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    models.forEach { model ->
                        val isSelected = model.name == selectedModel
                        Surface(
                            modifier = Modifier.clickable { onModelSelected(model.name) },
                            color = if (isSelected) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFF2A2A2A),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = model.name.removeSuffix(".onnx").removeSuffix(".tflite"),
                                color = if (isSelected) Color(0xFF4CAF50) else Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(16.dp))

            // 启动时显示此页
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启动时进入此配置页", fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                Switch(
                    checked = settings.startOnConfig,
                    onCheckedChange = onStartOnConfigChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF4CAF50),
                        checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.5f)
                    )
                )
            }

            Spacer(Modifier.height(8.dp))

            // 开始检测按钮
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    if (settings.appMode == AppMode.OPENMV) "开始脚本模式" else "开始检测",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White
                )
            }
        }
    }
}
