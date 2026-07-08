package com.py2roid

import android.app.Application

class Py2roidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Chaquopy Python 在 Application.onCreate() 中自动初始化
        // 不需要显式调用 Python.start()
    }
}
