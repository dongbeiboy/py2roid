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
    val y2: Float,
    /** 归一化参考帧的宽高比 (origWidth/origHeight)，用于 CameraPreview 校正 FILL_CENTER 坐标映射 */
    val frameAspect: Float = 4f / 3f
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
            color = color,
            frameAspect = frameAspect
        )
    }
}
