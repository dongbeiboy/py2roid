package com.xz.py2roid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp

data class ModelItem(
    val name: String,
    val inputSize: String = "",
    val classes: Int = 80,
    val isActive: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    models: List<ModelItem>,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    onImportClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("选择模型", style = MaterialTheme.typography.labelLarge)

            models.forEach { model ->
                ModelItemRow(
                    model = model,
                    isSelected = model.name == selectedModel,
                    onClick = {
                        onModelSelected(model.name)
                        onDismiss()
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Button(
                onClick = onImportClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导入模型")
            }
        }
    }
}

@Composable
private fun ModelItemRow(
    model: ModelItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = model.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (model.inputSize.isNotEmpty()) {
            Text(
                text = model.inputSize,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
