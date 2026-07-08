package com.xz.py2roid.ui

import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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

        // 检测框 + 标签叠加层
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            val textPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 36f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            val bgPaint = Paint().apply {
                isAntiAlias = true
            }

            for (box in boxes) {
                val left = box.x1 * canvasWidth
                val top = box.y1 * canvasHeight
                val right = box.x2 * canvasWidth
                val bottom = box.y2 * canvasHeight
                val w = right - left
                val h = bottom - top

                // 画框
                drawRect(
                    color = box.color,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(width = 3f)
                )

                // 标签文字
                val labelText = "${box.label} ${"%.0f".format(box.confidence * 100)}%"
                bgPaint.color = box.color.toArgb()
                val textWidth = textPaint.measureText(labelText)
                val textHeight = 36f

                // 标签背景
                drawContext.canvas.nativeCanvas.drawRoundRect(
                    left, top - textHeight - 6f,
                    left + textWidth + 12f, top + 4f,
                    4f, 4f, bgPaint
                )
                // 标签文字
                textPaint.color = android.graphics.Color.WHITE
                drawContext.canvas.nativeCanvas.drawText(
                    labelText,
                    left + 6f, top - 4f, textPaint
                )
            }
        }
    }
}
