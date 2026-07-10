package com.xz.py2roid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext

data class AppSettings(
    val confidenceThreshold: Float = 0.5f,
    val iouThreshold: Float = 0.45f,
    val commMode: CommMode = CommMode.USB,
    val inferenceBackend: InferenceBackend = InferenceBackend.Auto,
    val debugOverlayEnabled: Boolean = false,
    val startOnConfig: Boolean = true
)

enum class CommMode(val label: String) { USB("USB"), WiFi("WiFi"), Off("关闭") }
enum class InferenceBackend(val label: String) { Auto("自动"), CPU("CPU"), XNNPACK("XNNPACK"), NNAPI("NNAPI"), VCAP("VCAP"), TFLITE("TFLite"), TFLITE_GPU("TFLite GPU"), TFLITE_NNAPI("TFLite NNAPI") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    enabledBackends: Set<InferenceBackend> = InferenceBackend.entries.toSet(),
    currentProvider: String = "?",
    onConfidenceChange: (Float) -> Unit,
    onIouChange: (Float) -> Unit,
    onCommModeChange: (CommMode) -> Unit,
    onBackendChange: (InferenceBackend) -> Unit,
    onDebugOverlayChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onGoConfig: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 20.sp, color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("检测参数")
            SettingsCard {
                SliderWithLabel("置信度阈值", settings.confidenceThreshold, onConfidenceChange, 0.1f..0.95f, "%.2f")
                Spacer(Modifier.height(8.dp))
                SliderWithLabel("IoU 阈值", settings.iouThreshold, onIouChange, 0.1f..0.9f, "%.2f")
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("通讯模式")
            SettingsCard {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    CommMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = settings.commMode == m,
                            onClick = { onCommModeChange(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, CommMode.entries.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                inactiveContainerColor = Color(0xFF2A2A2A)
                            )
                        ) { Text(m.label, fontSize = 13.sp) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("推理后端")
            SettingsCard {
                InferenceBackendGrid(
                    selectedBackend = settings.inferenceBackend,
                    enabledBackends = enabledBackends,
                    onBackendChange = onBackendChange
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("调试")
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("日志覆盖层", fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                    Switch(
                        checked = settings.debugOverlayEnabled,
                        onCheckedChange = onDebugOverlayChange,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedThumbColor = Color.White
                        )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                val context = LocalContext.current
                val versionName = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "?" }
                Column(modifier = Modifier.padding(16.dp)) {
                    AboutRow("应用", "py2roid")
                    AboutRow("版本", versionName)
                    AboutRow("视觉引擎", currentProvider)
                    AboutRow("Python", "3.12 (Chaquopy)")
                }
            }

            Spacer(Modifier.height(16.dp))

            // → 预配置
            Text(
                text = "→ 预配置",
                color = Color(0xFF4CAF50),
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGoConfig() }
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SliderWithLabel(
    label: String, value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    format: String
) {
    Text(
        text = "$label  ${"$format".format(value)}",
        fontSize = 14.sp,
        color = Color.White.copy(alpha = 0.8f)
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = Color(0xFF333333),
            thumbColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFF4CAF50),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
        Text(value, fontSize = 13.sp, color = Color.White)
    }
}
