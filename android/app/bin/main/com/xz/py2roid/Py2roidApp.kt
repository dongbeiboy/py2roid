package com.xz.py2roid

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.opencv.android.OpenCVLoader

class Py2roidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化 OpenCV native 库
        if (!OpenCVLoader.initDebug()) {
            android.util.Log.e("py2roid", "OpenCV init failed")
        }

        // 显式启动 Chaquopy Python 运行时
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
