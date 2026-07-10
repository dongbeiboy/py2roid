package com.xz.py2roid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    models: List<ModelItem>,
    selectedModel: String,
    settings: AppSettings,
    enabledBackends: Set<InferenceBackend>,
    onModelSelected: (String) -> Unit,
    onBackendChange: (InferenceBackend) -> Unit,
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
            // 模型选择
            Text("模型", color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                models.forEach { model ->
                    val isSelected = model.name == selectedModel
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModelSelected(model.name) },
                        color = if (isSelected) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color(0xFF2A2A2A),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = model.name,
                                color = if (isSelected) Color(0xFF4CAF50) else Color.White,
                                fontSize = 13.sp
                            )
                            Text(
                                text = model.inputSize,
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // 推理后端
            Text("推理后端", color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            InferenceBackendGrid(
                selectedBackend = settings.inferenceBackend,
                enabledBackends = modelBackends,
                onBackendChange = onBackendChange
            )

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
                Text("开始检测", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}
