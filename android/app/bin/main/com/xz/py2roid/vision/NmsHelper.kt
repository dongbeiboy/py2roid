package com.xz.py2roid.vision

import kotlin.math.max
import kotlin.math.min

object NmsHelper {

    fun nms(
        detections: List<DetectionResult>,
        iouThreshold: Float,
        confidenceThreshold: Float
    ): List<DetectionResult> {
        // Step 1: filter by confidence threshold
        val filtered = detections.filter { it.confidence >= confidenceThreshold }
        if (filtered.isEmpty()) return emptyList()

        // Step 2: sort by confidence descending
        val sorted = filtered.sortedByDescending { it.confidence }

        val result = mutableListOf<DetectionResult>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            result.add(sorted[i])

            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(sorted[i], sorted[j]) > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }

        return result
    }

    private fun iou(a: DetectionResult, b: DetectionResult): Float {
        // Compute intersection coordinates
        val interLeft = max(a.x1, b.x1)
        val interTop = max(a.y1, b.y1)
        val interRight = min(a.x2, b.x2)
        val interBottom = min(a.y2, b.y2)

        val interWidth = max(0f, interRight - interLeft)
        val interHeight = max(0f, interBottom - interTop)
        val interArea = interWidth * interHeight
        if (interArea <= 0f) return 0f

        // Areas of both boxes
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)

        // Union area
        val unionArea = areaA + areaB - interArea
        if (unionArea <= 0f) return 0f

        return interArea / unionArea
    }
}
