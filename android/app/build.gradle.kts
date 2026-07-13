import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Chaquopy 用 apply(plugin) 而非 plugins {} 块
// plugins {} 块在 Kotlin DSL 下可能无法为 python {} 生成访问器
apply(plugin = "com.chaquo.python")

android {
    namespace = "com.xz.py2roid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xz.py2roid"
        minSdk = 24
        targetSdk = 36
        versionCode = 17
        versionName = "1.0.17"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // python { } 配置在 python.gradle 中（Groovy），通过 apply(from) 引入
    // 避免 Kotlin DSL 访问器生成问题——Chaquopy 插件实现细节

    // 签名信息优先级：环境变量 > keystore.properties 文件
    // GitHub CI 通过 secrets 注入环境变量，本地开发用 keystore.properties（已 gitignore）
    signingConfigs {
        create("release") {
            val keystorePropsFile = rootProject.file("keystore.properties")
            val keystoreProps = if (keystorePropsFile.exists()) {
                Properties().apply { load(keystorePropsFile.inputStream()) }
            } else null

            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: keystoreProps?.getProperty("keystore.password")
                ?: ""
            keyAlias = System.getenv("KEY_ALIAS")
                ?: keystoreProps?.getProperty("keystore.alias")
                ?: "py2roid"
            keyPassword = System.getenv("KEY_PASSWORD")
                ?: keystoreProps?.getProperty("keystore.keyPassword")
                ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // VCAP (vivo AI 推理加速)
    implementation(files("libs/vivo_vcap_v3.0.0.0.jar"))

    // USB Serial
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")

    // OpenCV (official AAR on Maven Central)
    implementation("org.opencv:opencv:5.0.0.1")

    // NanoHTTPD
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // Material
    implementation("com.google.android.material:material:1.12.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.robolectric:robolectric:4.13")
}

// Chaquopy 需要 repositories 中包含 chaquo maven
