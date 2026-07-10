package com.xz.py2roid.vision

import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImagePreprocessor {
    private const val TAG = "py2roid.ImagePreprocessor"

    private var cacheMat: Mat? = null
    private var cacheBitmap: Bitmap? = null

    fun preprocess(
        image: Image,
        targetWidth: Int = 640,
        targetHeight: Int = 640
    ): FloatArray {
        try {
            val t0 = System.currentTimeMillis()

            // 直接用 Android YuvImage 做 NV21→JPEG→Bitmap 转换
            // CameraX 的 YUV_420_888 各厂商排列不同，走 JPEG 编码器统一处理最稳
            val nv21 = nv21FromYuv420(image)
            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21,
                image.width, image.height, null)
            val rect = android.graphics.Rect(0, 0, image.width, image.height)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(rect, 100, out)
            val jpegData = out.toByteArray()
            out.close()
            val bitmap = cacheBitmap ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            cacheBitmap = bitmap
            val jpegBitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawBitmap(jpegBitmap, 0f, 0f, null)
            jpegBitmap.recycle()

            val t1 = System.currentTimeMillis()
            Log.d(TAG, "YUV→Bitmap ${image.width}x${image.height} = ${t1-t0}ms")
            val srcMat = bitmapToMat(bitmap)
            val t2 = System.currentTimeMillis()
            Log.d(TAG, "Bitmap→BGR mat = ${t2-t1}ms")

            // Resize to target dimensions
            val resizedMat = cacheMat ?: Mat()
            cacheMat = resizedMat
            Imgproc.resize(srcMat, resizedMat, Size(targetWidth.toDouble(), targetHeight.toDouble()))
            val t3 = System.currentTimeMillis()
            Log.d(TAG, "Resize ${srcMat.width()}x${srcMat.height()}→${targetWidth}x${targetHeight} = ${t3-t2}ms")

            // Convert BGR to RGB
            val rgbMat = Mat()
            Imgproc.cvtColor(resizedMat, rgbMat, Imgproc.COLOR_BGR2RGB)

            // Optional horizontal flip for mirror effect (front camera)
            // Core.flip(rgbMat, rgbMat, 1)

            // Split BGR (CV_8UC3) into 3 channels (CV_8UC1), then convert to float
            val channels8U = ArrayList<Mat>(3).apply {
                add(Mat())
                add(Mat())
                add(Mat())
            }
            Core.split(rgbMat, channels8U)

            val floatArray = FloatArray(3 * targetHeight * targetWidth)
            var offset = 0
            for (c8 in channels8U) {
                val c32f = Mat(targetHeight, targetWidth, CvType.CV_32FC1)
                c8.convertTo(c32f, CvType.CV_32FC1)
                val buf = FloatArray(targetHeight * targetWidth)
                c32f.get(0, 0, buf)
                buf.copyInto(floatArray, offset)
                offset += targetHeight * targetWidth
                c32f.release()
                c8.release()
            }

            // Normalize from [0, 255] to [0, 1]
            for (i in floatArray.indices) {
                floatArray[i] /= 255.0f
            }

            rgbMat.release()
            srcMat.release()

            val totalMs = System.currentTimeMillis() - t0
            Log.d(TAG, "Preprocess total=${totalMs}ms")

            return floatArray
        } catch (e: Exception) {
            Log.e(TAG, "[E01] preprocess failed: ${e.message}", e)
            throw e
        }
    }

    fun close() {
        cacheMat?.release()
        cacheMat = null
        cacheBitmap = null
    }

    /**
     * YUV_420_888 → NV21
     * 逐行拷贝，按 pixelStride 精确寻址，带边界保护
     */
    private var _yuvLogged = false
    private fun nv21FromYuv420(image: Image): ByteArray {
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

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat(bitmap.height, bitmap.width, CvType.CV_8UC4)
        Utils.bitmapToMat(bitmap, mat)
        val rgbMat = Mat()
        Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_RGBA2BGR)
        mat.release()
        return rgbMat
    }
}
