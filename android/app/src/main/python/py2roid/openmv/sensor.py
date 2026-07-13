"""
OpenMV sensor 模块的 CPython 实现。

核心职责：
- 维护传感器虚拟状态（分辨率、像素格式、镜像/翻转）
- snapshot() 通过 _internal.bridge 获取 CameraX 帧并创建 Image
"""

from __future__ import annotations

from typing import Optional

from . import image as _image_mod


class Sensor:
    """OpenMV Sensor 兼容实现。

    色彩空间常量与 OpenMV 一致。
    """

    # 色彩空间
    RGB565 = 1
    GRAYSCALE = 2

    # 分辨率
    VGA = (640, 480)
    QVGA = (320, 240)
    QQVGA = (160, 120)
    HQVGA = (240, 176)
    B64X64 = (64, 64)

    def __init__(self):
        self.reset()

    def reset(self):
        """重置传感器为默认配置。"""
        self._width = 640
        self._height = 480
        self._pixformat = self.RGB565
        self._framerate = 30
        self._hmirror = False
        self._vflip = False
        self._transpose = False
        self._auto_gain = True
        self._auto_whitebal = True
        self._auto_exposure = True
        self._brightness = 0
        self._contrast = 0
        self._saturation = 0
        self._bridge = None
        # 帧计数器（用于 skip_frames）
        self._skip_count = 0

    def set_pixformat(self, fmt: int):
        """设置像素格式。

        Args:
            fmt: RGB565 (1) 或 GRAYSCALE (2)
        """
        self._pixformat = fmt

    def set_framesize(self, size: tuple[int, int]):
        """设置帧尺寸。

        Args:
            size: (width, height)，如 VGA=(640,480)
        """
        self._width, self._height = size

    def set_framerate(self, fps: int):
        """设置帧率（仅记录，实际由 CameraX 控制）。"""
        self._framerate = fps

    def skip_frames(self, n: int = 60):
        """跳过 n 帧以稳定传感器。

        Args:
            n: 跳过的帧数
        """
        self._skip_count = n
        while self._skip_count > 0:
            self.snapshot()

    def set_hmirror(self, enable: bool):
        """设置水平镜像。"""
        self._hmirror = bool(enable)

    def set_vflip(self, enable: bool):
        """设置垂直翻转。"""
        self._vflip = bool(enable)

    def set_transpose(self, enable: bool):
        """设置转置。"""
        self._transpose = bool(enable)

    def set_auto_gain(self, enable: bool):
        """设置自动增益（仅记录，CameraX 自动管理）。"""
        self._auto_gain = bool(enable)

    def set_auto_whitebal(self, enable: bool):
        """设置自动白平衡（仅记录）。"""
        self._auto_whitebal = bool(enable)

    def set_auto_exposure(self, enable: bool):
        """设置自动曝光（仅记录）。"""
        self._auto_exposure = bool(enable)

    def set_brightness(self, val: int):
        """设置亮度（仅记录）。"""
        self._brightness = val

    def set_contrast(self, val: int):
        """设置对比度（仅记录）。"""
        self._contrast = val

    def set_saturation(self, val: int):
        """设置饱和度（仅记录）。"""
        self._saturation = val

    def snapshot(self) -> Optional["_image_mod.Image"]:
        """取一帧。阻塞——等待 PythonBridge 返回最新相机帧。

        Returns:
            Image 对象，或 None（帧尚未就绪）
        """
        if self._skip_count > 0:
            self._skip_count -= 1

        from ._internal import bridge as _bridge

        raw_data = _bridge.get_frame_bytes(
            self._width, self._height, self._pixformat
        )
        if raw_data is None:
            return None

        # 构造 Image
        if self._pixformat == Sensor.RGB565:
            import numpy as np
            arr = np.frombuffer(raw_data, dtype=np.uint16).reshape(
                self._height, self._width
            )
        else:
            import numpy as np
            arr = np.frombuffer(raw_data, dtype=np.uint8).reshape(
                self._height, self._width
            )

        img = _image_mod.Image(arr, self._width, self._height, self._pixformat)

        # 应用镜像/翻转/转置
        if self._hmirror:
            img.hmirror()
        if self._vflip:
            img.vflip()
        if self._transpose:
            img.transpose()

        return img

    def width(self) -> int:
        """返回当前帧宽度。"""
        return self._width

    def height(self) -> int:
        """返回当前帧高度。"""
        return self._height

    def get_fb(self):
        """返回帧缓冲区（当前 snapshot 结果）。"""
        return self.snapshot()


# OpenMV 兼容全局单例
sensor = Sensor()
