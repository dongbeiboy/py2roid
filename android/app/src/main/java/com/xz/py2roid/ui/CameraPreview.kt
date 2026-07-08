package com.xz.py2roid.ui

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.viewinterop.AndroidView

data class BoundingBox(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val label: String,
    val confidence: Float,
    val color: Color = Color(0xFF2196F3)
)

@Composable
fun CameraPreview(
    previewView: PreviewView,
    boxes: List<BoundingBox> = emptyList(),
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // CameraX PreviewView
        AndroidView(
            factory = { ctx ->
                previewView.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 检测框叠加层
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            for (box in boxes) {
                // 坐标从归一化 [0,1] 映射到 canvas 像素
                val left = box.x1 * canvasWidth
                val top = box.y1 * canvasHeight
                val right = box.x2 * canvasWidth
                val bottom = box.y2 * canvasHeight

                // 画边框
                drawRect(
                    color = box.color,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 3f)
                )
            }
        }
    }
}
