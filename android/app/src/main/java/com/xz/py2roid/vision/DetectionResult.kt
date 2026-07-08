package com.xz.py2roid.vision

import androidx.compose.ui.graphics.Color
import com.xz.py2roid.ui.BoundingBox

data class DetectionResult(
    val label: String,
    val classId: Int,
    val confidence: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
) {
    fun toBoundingBox(labelColors: Map<String, Color>): BoundingBox {
        val color = labelColors[label] ?: Color(0xFF2196F3)
        return BoundingBox(
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            label = label,
            confidence = confidence,
            color = color
        )
    }
}
