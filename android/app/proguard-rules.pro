# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Chaquopy
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# USB Serial
-keep class com.hoho.android.usbserial.** { *; }
-dontwarn com.hoho.android.usbserial.**

# Kotlin data classes (serialization)
-keepclassmembers class com.xz.py2roid.** {
    @kotlin.Metadata <fields>;
}

# JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# NanoHTTPD
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**

# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# TensorFlow Lite GPU delegate
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.gpu.**

# TensorFlow Lite support
-keep class org.tensorflow.lite.support.** { *; }
-dontwarn org.tensorflow.lite.support.**
