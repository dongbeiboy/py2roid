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
            val bitmap = imageToBitmap(image)
            val srcMat = bitmapToMat(bitmap)

            // Resize to target dimensions
            val resizedMat = cacheMat ?: Mat()
            cacheMat = resizedMat
            Imgproc.resize(srcMat, resizedMat, Size(targetWidth.toDouble(), targetHeight.toDouble()))

            // Convert BGR to RGB
            val rgbMat = Mat()
            Imgproc.cvtColor(resizedMat, rgbMat, Imgproc.COLOR_BGR2RGB)

            // Optional horizontal flip for mirror effect (front camera)
            // Core.flip(rgbMat, rgbMat, 1)

            // Convert to CHW float array [3 x H x W] normalized to [0, 1]
            val channels = listOf(
                Mat(targetHeight, targetWidth, CvType.CV_32FC1),
                Mat(targetHeight, targetWidth, CvType.CV_32FC1),
                Mat(targetHeight, targetWidth, CvType.CV_32FC1)
            )
            Core.split(rgbMat, channels)

            val floatArray = FloatArray(3 * targetHeight * targetWidth)
            var offset = 0
            for (channel in channels) {
                val buf = FloatArray(targetHeight * targetWidth)
                channel.get(0, 0, buf)
                buf.copyInto(floatArray, offset)
                offset += targetHeight * targetWidth
                channel.release()
            }

            // Normalize from [0, 255] to [0, 1]
            for (i in floatArray.indices) {
                floatArray[i] /= 255.0f
            }

            rgbMat.release()
            srcMat.release()

            return floatArray
        } catch (e: Exception) {
            Log.e(TAG, "preprocess failed", e)
            throw e
        }
    }

    fun close() {
        cacheMat?.release()
        cacheMat = null
        cacheBitmap = null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val yPlane = planes[0]
        val vuPlane = planes[2]

        val width = image.width
        val height = image.height

        val yBuffer = yPlane.buffer
        val vuBuffer = vuPlane.buffer

        val ySize = yBuffer.remaining()
        val vuSize = vuBuffer.remaining()

        val nv21 = ByteArray(ySize + vuSize)
        yBuffer.get(nv21, 0, ySize)
        vuBuffer.get(nv21, ySize, vuSize)

        val bitmap = cacheBitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        cacheBitmap = bitmap

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        val rect = android.graphics.Rect(0, 0, width, height)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(rect, 100, out)
        val jpegData = out.toByteArray()
        val jpegBitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
        out.close()

        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawBitmap(jpegBitmap, 0f, 0f, null)
        jpegBitmap.recycle()

        return bitmap
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
