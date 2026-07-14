package com.xz.py2roid.vision

import android.media.Image
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 预处理结果：包含推理输入张量和 letterbox 参数，
 * 用于 Detector 还原原始图像坐标。
 */
data class PreprocessResult(
    val tensor: FloatArray,
    /** 左 padding 像素（letterbox 添加的灰边） */
    val padLeft: Int,
    /** 上 padding 像素 */
    val padTop: Int,
    /** 缩放比例（原始图像→模型输入） */
    val scale: Float,
    /** 原始图像宽度 */
    val origWidth: Int,
    /** 原始图像高度 */
    val origHeight: Int
)

object ImagePreprocessor {
    private const val TAG = "py2roid.ImagePreprocessor"
    /** YOLO 默认 padding 值（近似自然图像均值） */
    private const val PAD_VALUE = 114.0

    /** Buffer pool: 避免每帧重新分配 ByteArray/FloatArray */
    private var _poolRgbPixels: ByteArray? = null
    private var _poolFloatTensor: FloatArray? = null
    private var _poolNv21Mat: Mat? = null
    private var _poolRgbMat: Mat? = null
    private var _poolResizedMat: Mat? = null
    private var _poolPaddedMat: Mat? = null

    /**
     * 预处理 CameraX 帧 → 模型输入张量。
     *
     * 优化流程：NV21 → RGB Mat → 旋转校正 → letterbox 等比缩放 + 灰边填充
     * → 单次读取 RGB 字节 → 交错分通道 → float32[3×H×W] → 归一化 [0,1]
     *
     * 相比原流程（NV21→BGR→RGB→split→3×convertTo），消除了：
     *   - 1 次 BGR↔RGB 转换
     *   - Core.split 产生的 3 个临时 Mat
     *   - 每通道 1 次 convertTo + 1 次 float32 Mat 分配
     *   共计减少 6+ 次 Mat 分配/帧，大幅降低 GC 压力。
     *
     * @param image CameraX Image（传感器原始帧）
     * @param rotationDegrees 图像旋转角度（0/90/180/270），取自 ImageProxy.imageInfo.rotationDegrees
     * @return [PreprocessResult]，内含张量 + 还原原始坐标所需的 letterbox 参数
     */
    fun preprocess(
        image: Image,
        rotationDegrees: Int = 0,
        targetWidth: Int = 640,
        targetHeight: Int = 640
    ): PreprocessResult {
        val nv21 = nv21FromYuv420(image)
        return preprocess(nv21, image.width, image.height, rotationDegrees, targetWidth, targetHeight)
    }

    /**
     * 预处理预提取的 NV21 字节数组 → 模型输入张量。
     *
     * 与 [preprocess(Image)] 共享同一处理管线，避免重复读取 Image planes。
     *
     * === 优化点 ===
     * 1. NV21→RGB 直出（跳过 BGR 中间态，节省 1 次 cvtColor）
     * 2. RGB 像素 → float32[3×H×W] 单次完成（跳过 Core.split + 3×convertTo + 额外归一化循环）
     * 3. Buffer pool 复用预分配数组和 Mat，减少 GC 抖动
     */
    fun preprocess(
        nv21: ByteArray,
        srcW: Int,
        srcH: Int,
        rotationDegrees: Int = 0,
        targetWidth: Int = 640,
        targetHeight: Int = 640
    ): PreprocessResult {
        try {
            val t0 = System.currentTimeMillis()

            // ── 1. NV21 → RGB Mat（直出，跳过 BGR 中间态） ──
            // 确保尺寸匹配：若分辨率变化则重建
            var nv21Mat = _poolNv21Mat
            if (nv21Mat == null || nv21Mat.rows() != srcH * 3 / 2 || nv21Mat.cols() != srcW) {
                nv21Mat?.release()
                Mat(srcH * 3 / 2, srcW, CvType.CV_8UC1).also { _poolNv21Mat = it; nv21Mat = it }
            }
            nv21Mat!!.put(0, 0, nv21)
            val rgbMat = _poolRgbMat?.takeIf { it.rows() == srcH && it.cols() == srcW }
                ?: Mat().also { _poolRgbMat = it }
            Imgproc.cvtColor(nv21Mat!!, rgbMat, Imgproc.COLOR_YUV2RGB_NV21)

            val t1 = System.currentTimeMillis()
            Log.d(TAG, "YUV→RGB ${srcW}x${srcH} = ${t1 - t0}ms")

            // ── 2. 旋转校正（传感器帧 → 屏幕方向） ──
            if (rotationDegrees != 0) {
                when (rotationDegrees) {
                    90 -> Core.rotate(rgbMat, rgbMat, Core.ROTATE_90_CLOCKWISE)
                    180 -> Core.rotate(rgbMat, rgbMat, Core.ROTATE_180)
                    270 -> Core.rotate(rgbMat, rgbMat, Core.ROTATE_90_COUNTERCLOCKWISE)
                }
                Log.d(TAG, "Rotated ${rotationDegrees}°")
            }

            // 旋转后宽高可能交换（90°/270°），用新变量避免遮蔽参数
            val rotW = rgbMat.width()
            val rotH = rgbMat.height()

            // ── 3. Letterbox：等比缩放 + 灰边填充 ──
            val scale = minOf(targetWidth.toFloat() / rotW, targetHeight.toFloat() / rotH)
            val newW = (rotW * scale).toInt().coerceAtLeast(1)
            val newH = (rotH * scale).toInt().coerceAtLeast(1)
            val padLeft = (targetWidth - newW) / 2
            val padTop = (targetHeight - newH) / 2

            // 等比缩放（复用 Mat）
            val resizedMat = _poolResizedMat?.takeIf {
                it.rows() >= newH && it.cols() >= newW && it.type() == CvType.CV_8UC3
            } ?: Mat().also { _poolResizedMat = it }
            Imgproc.resize(rgbMat, resizedMat, Size(newW.toDouble(), newH.toDouble()))
            val t3 = System.currentTimeMillis()
            Log.d(TAG, "Letterbox ${srcW}x${srcH}→${targetWidth}x${targetHeight} " +
                    "scale=%.3f new=${newW}x${newH} pad=($padLeft,$padTop) = ${t3 - t1}ms".format(scale))

            // 创建 640×640 填充图（灰边 114）— 复用 Mat
            val paddedMat = _poolPaddedMat?.takeIf { it.rows() == targetHeight && it.cols() == targetWidth && it.type() == CvType.CV_8UC3 }
                ?: Mat(targetHeight, targetWidth, CvType.CV_8UC3).also { _poolPaddedMat = it }
            paddedMat.setTo(Scalar.all(PAD_VALUE))
            resizedMat.copyTo(paddedMat.submat(padTop, padTop + newH, padLeft, padLeft + newW))

            // ── 4. 单次读取 RGB 像素 → float32[3×H×W] 归一化 ──
            val totalPixels = targetWidth * targetHeight
            val rgbPixels = _poolRgbPixels?.takeIf { it.size >= totalPixels * 3 }
                ?: ByteArray(totalPixels * 3).also { _poolRgbPixels = it }
            paddedMat.get(0, 0, rgbPixels)

            val floatArray = _poolFloatTensor?.takeIf { it.size >= 3 * totalPixels }
                ?: FloatArray(3 * totalPixels).also { _poolFloatTensor = it }

            // 单循环：字节交错 RGB → 平面分通道 float32 + 归一化 [0,1]
            for (i in 0 until totalPixels) {
                val base = i * 3
                floatArray[i] = (rgbPixels[base].toInt() and 0xFF) * 0.003921569f           // R
                floatArray[totalPixels + i] = (rgbPixels[base + 1].toInt() and 0xFF) * 0.003921569f // G
                floatArray[2 * totalPixels + i] = (rgbPixels[base + 2].toInt() and 0xFF) * 0.003921569f // B
            }

            val totalMs = System.currentTimeMillis() - t0
            Log.d(TAG, "Preprocess total=${totalMs}ms")

            return PreprocessResult(
                tensor = floatArray,
                padLeft = padLeft,
                padTop = padTop,
                scale = scale,
                origWidth = srcW,
                origHeight = srcH
            )
        } catch (e: Exception) {
            Log.e(TAG, "[E01] preprocess failed: ${e.message}", e)
            throw e
        }
    }

    fun close() {
        _poolNv21Mat?.release(); _poolNv21Mat = null
        _poolRgbMat?.release(); _poolRgbMat = null
        _poolResizedMat?.release(); _poolResizedMat = null
        _poolPaddedMat?.release(); _poolPaddedMat = null
        _poolRgbPixels = null
        _poolFloatTensor = null
    }

    /**
     * YUV_420_888 → NV21
     * 逐行拷贝，按 pixelStride 精确寻址，带边界保护
     */
    private var _yuvLogged = false
    /**
     * 从 CameraX YUV_420_888 Image 提取 NV21 格式数据。
     * 公开给 PythonBridge 帧缓存使用。
     */
    fun nv21FromYuv420(image: Image): ByteArray {
        val planes = image.planes
        val w = image.width
        val h = image.height
        val uvW = w / 2
        val uvH = h / 2
        val nv21 = ByteArray(w * h * 3 / 2)

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        // 首帧打印 YUV 布局
        if (!_yuvLogged) {
            _yuvLogged = true
            val uLimit = uBuf.limit()
            val vLimit = vBuf.limit()
            Log.w(TAG, "═══ YUV LAYOUT ═══")
            Log.w(TAG, "Size: ${w}x$h, NV21 expected=${nv21.size}")
            Log.w(TAG, "Y: ps=${yPlane.pixelStride} rs=$yRowStride limit=${yBuf.limit()}")
            Log.w(TAG, "U: ps=${uPlane.pixelStride} rs=${uPlane.rowStride} limit=$uLimit")
            Log.w(TAG, "V: ps=${vPlane.pixelStride} rs=${vPlane.rowStride} limit=$vLimit")
            Log.w(TAG, "Calc: Y=${yBuf.limit()} UV=${uLimit+vLimit} sum=${yBuf.limit()+uLimit+vLimit}")
            // 打印前几个字节看排列
            yBuf.mark()
            val yHead = ByteArray(16)
            yBuf.get(yHead)
            yBuf.reset()
            Log.w(TAG, "Y head[0..15]: ${yHead.joinToString { "%02x".format(it) }}")
            uBuf.mark()
            val uHead = ByteArray(16)
            uBuf.get(uHead)
            uBuf.reset()
            Log.w(TAG, "U head[0..15]: ${uHead.joinToString { "%02x".format(it) }}")
            vBuf.mark()
            val vHead = ByteArray(16)
            vBuf.get(vHead)
            vBuf.reset()
            Log.w(TAG, "V head[0..15]: ${vHead.joinToString { "%02x".format(it) }}")
            Log.w(TAG, "══════════════════")
        }
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, row * w, w)
        }

        // UV：安全寻址（部分设备 buffer limit 小于 rowStride * uvH）
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPs = uPlane.pixelStride
        val vPs = vPlane.pixelStride
        val uLimit = uBuf.limit()
        val vLimit = vBuf.limit()
        var uvOff = w * h

        for (row in 0 until uvH) {
            val uRowBase = row * uRowStride
            val vRowBase = row * vRowStride
            for (col in 0 until uvW) {
                val uPos = uRowBase + col * uPs
                val vPos = vRowBase + col * vPs
                if (vPos >= vLimit || uPos >= uLimit) {
                    // buffer 提前结束，剩余用 0 填充
                    if (uvOff < nv21.size) { nv21[uvOff++] = 0; nv21[uvOff++] = 0 }
                    continue
                }
                vBuf.position(vPos)
                uBuf.position(uPos)
                nv21[uvOff++] = vBuf.get()
                nv21[uvOff++] = uBuf.get()
            }
        }

        return nv21
    }

}
