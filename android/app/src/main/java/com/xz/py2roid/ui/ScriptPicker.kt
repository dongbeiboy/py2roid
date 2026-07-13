package com.xz.py2roid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ScriptFile(
    val name: String,
    val displayName: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptPicker(
    scripts: List<ScriptFile>,
    selectedScript: ScriptFile?,
    onScriptSelected: (ScriptFile) -> Unit,
    onImportClick: () -> Unit,
    onDeployExamples: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1E1E1E)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                "选择脚本",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportClick,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF333333)
                    )
                ) { Text("导入脚本", fontSize = 13.sp) }

                Button(
                    onClick = onDeployExamples,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32)
                    )
                ) { Text("内置示例", fontSize = 13.sp) }
            }

            Spacer(Modifier.height(12.dp))

            if (scripts.isEmpty()) {
                Text(
                    "暂无脚本，请导入或部署内置示例",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                Text(
                    "可用脚本 (${scripts.size})",
                    fontSize = 12.sp,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))

                LazyColumn(modifier = Modifier.height((scripts.size * 56).coerceAtMost(320).dp)) {
                    items(scripts) { script ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onScriptSelected(script) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "📜 ",
                                fontSize = 16.sp
                            )
                            Text(
                                script.displayName,
                                fontSize = 14.sp,
                                color = if (script == selectedScript)
                                    Color(0xFF4CAF50)
                                else
                                    Color.White.copy(alpha = 0.85f),
                                fontWeight = if (script == selectedScript) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
