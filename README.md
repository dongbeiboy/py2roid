# py2roid

> **Python on Android** — 在 Android 手机上运行实时视觉识别（YOLO），通过 USB 串口或 WebSocket 将检测结果发送给嵌入式设备（STM32、K230、OpenMV 等）。
>
> ⚠️ 本项目由 AI 辅助开发，**请勿用于生产环境**。

## 简介

py2roid 实现 **"手机做视觉，单片机做控制"** 的架构——用 Android 手机的摄像头和算力做 YOLO 目标检测，再通过 USB 串口或 WiFi/WebSocket 将坐标和标签实时推送给嵌入式设备。

### 典型场景

- 📷 手机摄像头实时识别目标 → USB 串口发送坐标/标签 → 单片机执行机械动作
- 📡 手机作为视觉网关 → WiFi/WebSocket 广播检测结果 → 局域网内多设备消费

## 功能特性

- **YOLO 实时推理** — ONNX Runtime + NNAPI / VIVO VCAP 硬件加速，NPU → GPU → CPU 自动降级
- **CameraX 相机采集** — 生命感知，YUV_420_888 帧回调，OpenCV 加速预处理
- **USB 串口通讯** — 支持 CDC / FTDI / CH340 / CP210x，断线自动重连
- **WebSocket 广播** — 内置 HTTP 服务（NanoHTTPD），局域网内多客户端实时订阅
- **Python 业务层** — 通过 Chaquopy 内嵌 CPython，协议封装 / MCU 命令解析走 Python
- **模型热切换** — 支持内置模型 + 用户导入 .onnx 文件，运行时无缝切换
- **Material3 UI** — Jetpack Compose 暗色主题，HUD 叠加显示 FPS / 目标数 / 推理后端

## 技术栈

| 层 | 技术 | 职责 |
|---|---|---|
| 壳应用 | Kotlin + Jetpack Compose | 原生 Android UI，Gradle 构建 |
| Python 运行时 | Chaquopy | Android 进程内嵌 CPython 3.12 |
| 摄像头 | CameraX | 官方相机 API，生命周期感知 |
| 视觉预处理 | OpenCV 5.0 Android SDK | YUV → float[] 张量，NEON 加速 |
| YOLO 推理 | ONNX Runtime + VIVO VCAP | NNAPI / NPU 硬件加速，CPU 兜底 |
| USB 通讯 | usb-serial-for-android | CDC / FTDI / CH340 / CP210x |
| Web 通讯 | NanoHTTPD | HTTP + WebSocket 广播 |
| Python 桥接 | Chaquopy PythonBridge | Kotlin ↔ Python 双向调用 |

### 架构概览

```
CameraX Frame (YUV_420_888)
    ↓
[Kotlin] YUV → float[] 张量 (OpenCV)
    ↓
[Kotlin] ONNX Runtime 推理 ← NNAPI / VIVO VCAP 硬件加速
    ↓
[Kotlin] 输出解析 + NMS → List<DetectionResult>
    ↓
[Chaquopy 桥接] 传递结构化数据 (坐标/标签/置信度)
    ↓
[Python] protocol.py 封装为 MCU 协议帧 → bytes
    ↓
[Kotlin] USB 串口发送 / WebSocket 广播
```

> **关键约束**：ONNX Runtime 和 OpenCV 走 Kotlin 原生层（AAR），不经过 Python，避免 GIL 限制和 Chaquopy pip 兼容问题。

## 快速开始

### 环境要求

- **JDK 21+**
- **Android SDK** — compileSdk 36, minSdk 24, build-tools 36.1.0
- **Python 3.12**（用于 Chaquopy 构建）

### 构建

```bash
cd android
./gradlew assembleDebug
```

APK 输出在 `android/app/build/outputs/apk/debug/`。

### 安装运行

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

首次启动会从 assets 解压内置 YOLOv8n 模型，之后可在设置中导入自定义 .onnx 模型。

## 项目结构

```
py2roid/
├── android/                          # Android Gradle 项目
│   └── app/src/main/
│       ├── java/com/xz/py2roid/
│       │   ├── vision/               # 视觉管线（推理引擎、预处理、NMS）
│       │   ├── ui/                   # Compose UI（主界面、HUD、设置、模型选择）
│       │   ├── bridge/               # Chaquopy Kotlin↔Python 桥接
│       │   ├── service/              # 前台服务（保持后台检测）
│       │   └── util/                 # 日志、权限等工具类
│       ├── python/                   # Python 业务层（协议、解析、工具）
│       ├── assets/                   # 内置 ONNX 模型
│       └── res/                      # Android 资源
├── tools/                            # PC 端开发辅助工具
│   ├── export_onnx/                  # PyTorch YOLO → ONNX 转换
│   ├── model_downloader.py           # 预训练模型下载
│   └── usb_test_client.py            # USB 串口模拟客户端
└── plan.md                           # 详细项目计划书
```

## 许可证

本项目采用 [MIT](LICENSE) 许可证。
