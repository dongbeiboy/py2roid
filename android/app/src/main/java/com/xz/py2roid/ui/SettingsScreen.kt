package com.xz.py2roid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext

data class AppSettings(
    val confidenceThreshold: Float = 0.5f,
    val iouThreshold: Float = 0.45f,
    val commMode: CommMode = CommMode.USB,
    val inferenceBackend: InferenceBackend = InferenceBackend.Auto,
    val debugOverlayEnabled: Boolean = false,
    val debugOverlayLevelFilter: Set<String> = setOf("E", "W", "I"),
    val debugOverlayHiddenCategories: Set<String> = emptySet(),
    val startOnConfig: Boolean = true,
    val appMode: AppMode = AppMode.LEGACY
)

enum class AppMode(val label: String) { LEGACY("原始模式"), OPENMV("OpenMV 模式") }

enum class CommMode(val label: String) { USB("USB"), WiFi("WiFi"), Off("关闭") }
enum class InferenceBackend(val label: String) {
    Auto("自动"), CPU("CPU"), XNNPACK("XNNPACK"), NNAPI("NNAPI"),
    VCAP("VCAP"),
    TFLITE("TFLite"), TFLITE_GPU("TFLite GPU"), TFLITE_NNAPI("TFLite NNAPI");

    companion object {
        /** 根据模型文件名过滤可用后端 */
        fun backendsForModel(
            modelName: String,
            allEnabled: Set<InferenceBackend>
        ): Set<InferenceBackend> {
            val isTflite = modelName.endsWith(".tflite", ignoreCase = true)
            return allEnabled.filter { b ->
                if (isTflite) b.name.startsWith("TFLITE")
                else !b.name.startsWith("TFLITE")
            }.toSet() + Auto // Auto 始终可用
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    onDebugOverlayLevelFilterChange: (Set<String>) -> Unit = {},
    onDebugOverlayHiddenCategoriesChange: (Set<String>) -> Unit = {},
    availableCategories: Set<String> = emptySet(),
    onAppModeChange: (AppMode) -> Unit = {},
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
            SectionHeader("运行模式")
            SettingsCard {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AppMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = settings.appMode == m,
                            onClick = { onAppModeChange(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, AppMode.entries.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                inactiveContainerColor = Color(0xFF2A2A2A)
                            )
                        ) { Text(m.label, fontSize = 13.sp) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (settings.appMode == AppMode.LEGACY) "Kotlin YOLO 检测管线" else "运行 MicroPython 兼容脚本",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("调试")
            SettingsCard {
                var showLevelMenu by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        Text(
                            text = "日志覆盖层",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.combinedClickable(
                                onClick = { /* 单击无操作，避免与 Switch 冲突 */ },
                                onLongClick = { showLevelMenu = true }
                            )
                        )

                        DropdownMenu(
                            expanded = showLevelMenu,
                            onDismissRequest = { showLevelMenu = false },
                            offset = DpOffset(0.dp, 0.dp),
                            modifier = Modifier
                                .background(Color(0xFF252526), RoundedCornerShape(8.dp))
                                .width(200.dp)
                        ) {
                            Text(
                                text = "显示级别",
                                fontSize = 12.sp,
                                color = Color(0xFF888888),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                            HorizontalDivider(color = Color(0xFF333333))

                            val allLevels = listOf(
                                "E" to "错误",
                                "W" to "警告",
                                "I" to "信息"
                            )
                            allLevels.forEach { (level, label) ->
                                val checked = level in settings.debugOverlayLevelFilter
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Checkbox(
                                                checked = checked,
                                                onCheckedChange = null,
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = Color(0xFF4CAF50),
                                                    uncheckedColor = Color(0xFF666666),
                                                    checkmarkColor = Color.White
                                                )
                                            )
                                            val levelColor = when (level) {
                                                "E" -> Color(0xFFFF5252)
                                                "W" -> Color(0xFFFFD740)
                                                else -> Color(0xFF69F0AE)
                                            }
                                            Text(
                                                text = "[$level] $label",
                                                fontSize = 13.sp,
                                                color = levelColor
                                            )
                                        }
                                    },
                                    onClick = {
                                        val updated = if (checked)
                                            settings.debugOverlayLevelFilter - level
                                        else
                                            settings.debugOverlayLevelFilter + level
                                        onDebugOverlayLevelFilterChange(if (updated.isEmpty()) setOf(level) else updated)
                                    },
                                    modifier = Modifier.padding(0.dp)
                                )
                            }

                            if (settings.debugOverlayLevelFilter.isNotEmpty()) {
                                HorizontalDivider(color = Color(0xFF333333))
                                val filterSummary = settings.debugOverlayLevelFilter.sorted().joinToString(", ")
                                Text(
                                    text = "当前: $filterSummary",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }

                            // ── 类别过滤 ──
                            if (availableCategories.isNotEmpty()) {
                                HorizontalDivider(color = Color(0xFF333333))
                                Text(
                                    text = "隐藏类别",
                                    fontSize = 12.sp,
                                    color = Color(0xFF888888),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                HorizontalDivider(color = Color(0xFF333333))

                                val sortedCats = availableCategories.sorted()
                                sortedCats.forEach { cat ->
                                    val hidden = cat in settings.debugOverlayHiddenCategories
                                    val showCat = !hidden
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Checkbox(
                                                    checked = showCat,
                                                    onCheckedChange = null,
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = Color(0xFF4CAF50),
                                                        uncheckedColor = Color(0xFF666666),
                                                        checkmarkColor = Color.White
                                                    )
                                                )
                                                Text(
                                                    text = "[$cat]",
                                                    fontSize = 13.sp,
                                                    color = Color(0xFF569CD6)
                                                )
                                                Text(
                                                    text = if (hidden) " 隐藏" else "",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF666666)
                                                )
                                            }
                                        },
                                        onClick = {
                                            val updated = if (hidden)
                                                settings.debugOverlayHiddenCategories - cat
                                            else
                                                settings.debugOverlayHiddenCategories + cat
                                            onDebugOverlayHiddenCategoriesChange(updated)
                                        },
                                        modifier = Modifier.padding(0.dp)
                                    )
                                }
                            }
                        }
                    }

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
