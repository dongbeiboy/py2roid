package com.xz.py2roid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun InferenceBackendGrid(
    selectedBackend: InferenceBackend,
    enabledBackends: Set<InferenceBackend>,
    onBackendChange: (InferenceBackend) -> Unit,
    columns: Int = 3,
    modifier: Modifier = Modifier
) {
    val backends = InferenceBackend.entries
    val rows = backends.chunked(columns)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { backend ->
                    val enabled = backend in enabledBackends
                    val isSelected = selectedBackend == backend
                    BackendCell(
                        label = backend.label,
                        selected = isSelected,
                        enabled = enabled,
                        onClick = { if (enabled) onBackendChange(backend) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BackendCell(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        !enabled -> Color(0xFF1A1A1A)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        else -> Color(0xFF2A2A2A)
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val textColor = when {
        !enabled -> Color.Gray
        selected -> MaterialTheme.colorScheme.primary
        else -> Color.White
    }

    Surface(
        modifier = modifier
            .height(52.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(if (selected) 1.5.dp else 0.dp, borderColor)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(label, fontSize = 12.sp, color = textColor)
                if (selected) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "✓",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
