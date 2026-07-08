package com.xz.py2roid.util

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class PermissionHelper(private val activity: ComponentActivity) {

    private var onAllGranted: (() -> Unit)? = null

    /** 请求 CAMERA + POST_NOTIFICATIONS（Android 13+） */
    fun requestCamera(onGranted: () -> Unit) {
        onAllGranted = onGranted
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onAllGranted?.invoke()
            return
        }
        launcher.launch(missing.toTypedArray())
    }

    fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) onAllGranted?.invoke()
    }
}
