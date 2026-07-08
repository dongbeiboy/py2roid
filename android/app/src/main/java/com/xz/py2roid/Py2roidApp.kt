package com.xz.py2roid

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class Py2roidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 显式启动 Chaquopy Python 运行时
        // 部分设备上字节码织入自动启动不可靠
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
