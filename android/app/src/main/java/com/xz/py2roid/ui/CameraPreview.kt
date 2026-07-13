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
    val color: Color = Color(0xFF2196F3),
    /** 归一化参考帧的宽高比 (origWidth/origHeight)，用于校正 FILL_CENTER 坐标映射 */
    val frameAspect: Float = 4f / 3f
)

/**
 * 相机预览 + 检测框叠加。
 *
 * 平板(4:3)上帧画面与屏幕比例一致，直映射即可。
 * 手机(20:9)上 FIT_CENTER 会产生黑边，需计算偏移量。
 */
@Composable
fun CameraPreview(
    previewView: PreviewView,
    boxes: List<BoundingBox> = emptyList(),
    /** 无检测框时使用的默认帧宽高比（与 CameraX targetResolution 一致，默认 4:3） */
    defaultFrameAspect: Float = 4f / 3f,
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
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 检测框叠加 — 校正 FIT_CENTER 黑边偏移
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val canvasAspect = canvasWidth / canvasHeight

            // ── FIT_CENTER: 等比缩放使画面完整可见，居中 ──
            val frameAspect = boxes.firstOrNull()?.frameAspect ?: defaultFrameAspect
            val rw: Float   // 画面在 Canvas 中的渲染宽度
            val rh: Float   // 画面在 Canvas 中的渲染高度
            val ox: Float   // 画面左上角在 Canvas 中的 X 偏移（黑边宽度）
            val oy: Float

            if (frameAspect >= canvasAspect) {
                // 画面比屏幕"宽" → 宽度撑满，上下黑边
                rw = canvasWidth
                rh = canvasWidth / frameAspect
                ox = 0f
                oy = (canvasHeight - rh) / 2f
            } else {
                // 画面比屏幕"高" → 高度撑满，左右黑边
                rh = canvasHeight
                rw = canvasHeight * frameAspect
                ox = (canvasWidth - rw) / 2f
                oy = 0f
            }

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
                val left = ox + box.x1 * rw
                val top = oy + box.y1 * rh
                val right = ox + box.x2 * rw
                val bottom = oy + box.y2 * rh
                val w = right - left
                val h = bottom - top

                drawRect(
                    color = box.color,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(width = 3f)
                )

                val labelText = "${box.label} ${"%.0f".format(box.confidence * 100)}%"
                bgPaint.color = box.color.toArgb()
                val textWidth = textPaint.measureText(labelText)
                val textHeight = 36f

                drawContext.canvas.nativeCanvas.drawRoundRect(
                    left, top - textHeight - 6f,
                    left + textWidth + 12f, top + 4f,
                    4f, 4f, bgPaint
                )
                textPaint.color = android.graphics.Color.WHITE
                drawContext.canvas.nativeCanvas.drawText(
                    labelText,
                    left + 6f, top - 4f, textPaint
                )
            }
        }
    }
}
