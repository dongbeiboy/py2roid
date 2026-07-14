package com.xz.py2roid.vision

import kotlin.math.max
import kotlin.math.min

/**
 * 非极大值抑制（Non-Maximum Suppression）。
 *
 * === 优化点 ===
 * 1. 将 iou 内联为局部函数，避免方法调用开销
 * 2. 在检测到 interWidth/interHeight <= 0 时直接返回，跳过面积计算
 * 3. 用 FloatArray 缓存面积，避免重复计算（每个 box 的面积在 NMS 中会被多次访问）
 */
object NmsHelper {

    fun nms(
        detections: List<DetectionResult>,
        iouThreshold: Float
    ): List<DetectionResult> {
        val n = detections.size
        if (n <= 1) return detections

        // 按置信度降序排列，用 ArrayList 避免 sortedByDescending 的 List 中间分配
        val sorted = if (detections is ArrayList) {
            detections.apply { sortByDescending { it.confidence } }
        } else {
            detections.sortedByDescending { it.confidence }
        }

        val result = ArrayList<DetectionResult>(minOf(n, 32))
        val suppressed = BooleanArray(n)

        // 预计算所有 box 的面积，避免在 iou 计算中重复计算
        val areas = FloatArray(n) { i ->
            val d = sorted[i]
            (d.x2 - d.x1) * (d.y2 - d.y1)
        }

        var i = 0
        while (i < n) {
            if (suppressed[i]) { i++; continue }
            result.add(sorted[i])

            val ai = areas[i]
            val xi1 = sorted[i].x1
            val yi1 = sorted[i].y1
            val xi2 = sorted[i].x2
            val yi2 = sorted[i].y2

            var j = i + 1
            while (j < n) {
                if (!suppressed[j]) {
                    // 快速相交判断：有一个轴不相交则跳过
                    val interLeft = max(xi1, sorted[j].x1)
                    val interTop = max(yi1, sorted[j].y1)
                    val interRight = min(xi2, sorted[j].x2)
                    val interBottom = min(yi2, sorted[j].y2)
                    val interW = interRight - interLeft
                    val interH = interBottom - interTop

                    if (interW > 0f && interH > 0f) {
                        val interArea = interW * interH
                        // 用预计算的面积
                        val unionArea = ai + areas[j] - interArea
                        if (unionArea > 0f && (interArea / unionArea) > iouThreshold) {
                            suppressed[j] = true
                        }
                    }
                }
                j++
            }
            i++
        }

        return result
    }
}
