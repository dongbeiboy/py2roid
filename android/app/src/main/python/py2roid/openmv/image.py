"""
OpenMV Image 类的 CPython 实现。

底层使用 numpy.ndarray 存储像素数据。
支持的格式：
- RGB565: ndarray (H, W) dtype=uint16
- GRAYSCALE: ndarray (H, W) dtype=uint8
"""

from __future__ import annotations

import io
import math
from typing import Optional

import numpy as np
from PIL import Image as PILImage


class Image:
    """OpenMV Image 兼容实现。"""

    RGB565 = 1
    GRAYSCALE = 2

    # 边缘检测类型
    EDGE_CANNY = 1
    EDGE_SIMPLE = 2

    # ── 构造 ──

    def __init__(
        self,
        data: np.ndarray,
        width: int,
        height: int,
        pixformat: int = RGB565,
        copy: bool = False,
    ):
        self._data = data.copy() if copy else data
        self._w = width
        self._h = height
        self._fmt = pixformat

    # ── 基础属性 ──

    def width(self) -> int:
        return self._w

    def height(self) -> int:
        return self._h

    def size(self) -> int:
        return self._w * self._h * (2 if self._fmt == self.RGB565 else 1)

    def format(self) -> int:
        return self._fmt

    def copy(self) -> "Image":
        return Image(self._data, self._w, self._h, self._fmt, copy=True)

    def bytes(self) -> bytes:
        return self._data.tobytes()

    def compressed(self, quality: int = 90) -> bytes:
        """JPEG 压缩。"""
        pil = self._to_pil()
        buf = io.BytesIO()
        pil.save(buf, format="JPEG", quality=quality)
        return buf.getvalue()

    def clear(self):
        """清空图像为全黑。"""
        self._data.fill(0)

    # ── 色彩空间转换 ──

    def to_grayscale(self) -> "Image":
        """转换为灰度图。"""
        if self._fmt == self.GRAYSCALE:
            return self.copy()
        # RGB565 → GRAYSCALE (uint16 little-endian: R[15:11] G[10:5] B[4:0])
        arr16 = self._data
        r = (((arr16 >> 11) & 0x1F) << 3).astype(np.float32)
        g = (((arr16 >> 5) & 0x3F) << 2).astype(np.float32)
        b = ((arr16 & 0x1F) << 3).astype(np.float32)
        gray = (0.299 * r + 0.587 * g + 0.114 * b).astype(np.uint8)
        return Image(gray, self._w, self._h, self.GRAYSCALE)

    def to_rgb565(self) -> "Image":
        """转换为 RGB565。"""
        if self._fmt == self.RGB565:
            return self.copy()
        # GRAYSCALE → RGB565
        g = self._data.astype(np.uint16)
        rgb565 = ((g >> 3) << 11) | ((g >> 2) << 5) | (g >> 3)
        return Image(rgb565, self._w, self._h, self.RGB565)

    def to_rgb888(self) -> np.ndarray:
        """返回 RGB888 numpy 数组 (H, W, 3) uint8。"""
        if self._fmt == self.RGB565:
            arr = self._data.view(np.uint8).reshape(self._h, self._w * 2)
            r = ((arr[:, 0::2].astype(np.uint16) << 3) & 0xF8).astype(np.uint8)
            g = (((arr[:, 0::2].astype(np.uint16) << 5) | (arr[:, 1::2].astype(np.uint16) >> 3)) & 0xFC).astype(np.uint8)
            b = ((arr[:, 1::2].astype(np.uint16) << 3) & 0xF8).astype(np.uint8)
            return np.stack([r, g, b], axis=2)
        # GRAYSCALE
        return np.stack([self._data] * 3, axis=2)

    def _to_pil(self) -> PILImage:
        """转换为 PIL Image（用于压缩/保存）。"""
        if self._fmt == self.GRAYSCALE:
            return PILImage.fromarray(self._data, mode="L")
        # RGB565 → PIL RGB
        rgb = self.to_rgb888()
        return PILImage.fromarray(rgb, mode="RGB")

    # ── 几何变换 ──

    def hmirror(self):
        """水平镜像。"""
        self._data = np.fliplr(self._data)

    def vflip(self):
        """垂直翻转。"""
        self._data = np.flipud(self._data)

    def transpose(self):
        """转置（交换宽高）。"""
        self._data = np.transpose(self._data)
        self._w, self._h = self._h, self._w

    def resize(self, w: int, h: int):
        """缩放到指定尺寸。"""
        if self._fmt == self.GRAYSCALE:
            pil = PILImage.fromarray(self._data, mode="L")
            pil = pil.resize((w, h), PILImage.NEAREST)
            self._data = np.array(pil, dtype=np.uint8)
        else:
            rgb = self.to_rgb888()
            pil = PILImage.fromarray(rgb, mode="RGB")
            pil = pil.resize((w, h), PILImage.NEAREST)
            rgb_small = np.array(pil, dtype=np.uint8)
            # RGB → RGB565
            r = rgb_small[:, :, 0]
            g = rgb_small[:, :, 1]
            b = rgb_small[:, :, 2]
            self._data = (np.uint16(r >> 3) << 11) | (np.uint16(g >> 2) << 5) | np.uint16(b >> 3)
        self._w, self._h = w, h

    def mean_pool(self, x_div: int, y_div: int):
        """均值池化降采样。"""
        out_h = self._h // y_div
        out_w = self._w // x_div
        pooled = self._data[:out_h * y_div, :out_w * x_div].reshape(
            out_h, y_div, out_w, x_div
        ).mean(axis=(1, 3))
        if self._fmt == self.RGB565:
            pooled = pooled.astype(np.uint16)
        else:
            pooled = pooled.astype(np.uint8)
        self._data = pooled
        self._w, self._h = out_w, out_h

    # ── 绘图 ──

    def _ensure_rgb888(self) -> np.ndarray:
        """确保当前数据可用于绘制（返回 RGB888 视图）。"""
        return self.to_rgb888()

    def draw_rectangle(
        self, x: int, y: int, w: int, h: int,
        color: tuple = (255, 0, 0), thickness: int = 1, fill: bool = False,
    ):
        """绘制矩形。"""
        rgb = self._ensure_rgb888()
        x1, y1 = max(0, x), max(0, y)
        x2, y2 = min(self._w, x + w), min(self._h, y + h)
        if fill:
            rgb[y1:y2, x1:x2] = color
        elif thickness > 0:
            rgb[y1:y2, x1:x1 + thickness] = color
            rgb[y1:y2, x2 - thickness:x2] = color
            rgb[y1:y1 + thickness, x1:x2] = color
            rgb[y2 - thickness:y2, x1:x2] = color

    def draw_circle(
        self, cx: int, cy: int, r: int,
        color: tuple = (255, 0, 0), thickness: int = 1, fill: bool = False,
    ):
        """绘制圆。"""
        rgb = self._ensure_rgb888()
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                dist = math.sqrt(dx * dx + dy * dy)
                px, py = cx + dx, cy + dy
                if 0 <= px < self._w and 0 <= py < self._h:
                    if fill:
                        if dist <= r:
                            rgb[py, px] = color
                    elif abs(dist - r) <= thickness:
                        rgb[py, px] = color

    def draw_line(
        self, x0: int, y0: int, x1: int, y1: int,
        color: tuple = (255, 0, 0), thickness: int = 1,
    ):
        """绘制线段（Bresenham）。"""
        rgb = self._ensure_rgb888()
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        while True:
            if 0 <= x0 < self._w and 0 <= y0 < self._h:
                rgb[y0, x0] = color
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def draw_cross(
        self, x: int, y: int,
        color: tuple = (255, 0, 0), size: int = 5, thickness: int = 1,
    ):
        """绘制十字。"""
        self.draw_line(x - size, y, x + size, y, color, thickness)
        self.draw_line(x, y - size, x, y + size, color, thickness)

    def draw_string(
        self, x: int, y: int, text: str,
        color: tuple = (255, 0, 0), size: int = 1,
    ):
        """绘制文本（使用 PIL 渲染后粘贴）。"""
        rgb = self._ensure_rgb888()
        try:
            from PIL import ImageDraw, ImageFont
            pil = PILImage.fromarray(rgb, mode="RGB")
            draw = ImageDraw.Draw(pil)
            try:
                font = ImageFont.truetype("Arial", size=size * 10)
            except OSError:
                font = ImageFont.load_default()
            draw.text((x, y), text, fill=color, font=font)
            self._data = np.array(pil)
        except Exception:
            pass

    def draw_arrow(
        self, x0: int, y0: int, x1: int, y1: int,
        color: tuple = (255, 0, 0), thickness: int = 1,
    ):
        """绘制箭头。"""
        self.draw_line(x0, y0, x1, y1, color, thickness)
        dx, dy = x1 - x0, y1 - y0
        length = math.sqrt(dx * dx + dy * dy)
        if length == 0:
            return
        ux, uy = dx / length, dy / length
        head_len = max(5, thickness * 3)
        px = -uy * head_len
        py = ux * head_len
        self.draw_line(x1, y1, int(x1 + px - ux * head_len), int(y1 + py - uy * head_len), color, thickness)
        self.draw_line(x1, y1, int(x1 - px - ux * head_len), int(y1 - py - uy * head_len), color, thickness)

    def draw_image(self, img: "Image", x: int, y: int):
        """叠加另一张图像。"""
        overlay = img.to_rgb888()
        rgb = self._ensure_rgb888()
        oh, ow = overlay.shape[:2]
        x1, y1 = max(0, x), max(0, y)
        x2, y2 = min(self._w, x + ow), min(self._h, y + oh)
        ox = x1 - x
        oy = y1 - y
        rgb[y1:y2, x1:x2] = overlay[oy:oy + y2 - y1, ox:ox + x2 - x1]

    # ── 图像处理 ──

    def binary(
        self, thresholds, invert: bool = False, zero: bool = False,
    ):
        """阈值二值化。

        Args:
            thresholds: GRAYSCALE 模式下为 [(min, max)]，
                       RGB565 模式下为 [(L_min, L_max, A_min, A_max, B_min, B_max)]
            invert: 是否反转
            zero: 是否将阈值外的像素置零（而非全白/全黑）
        """
        gray = self._fmt == self.GRAYSCALE
        if gray:
            data = self._data.astype(np.uint8)
        else:
            # RGB565 → 近似 LAB（简化：RGB → GRAY 近似）
            data = self.to_grayscale()._data

        mask = np.zeros(data.shape, dtype=bool)
        for t in thresholds:
            if gray:
                lo, hi = t[0], t[1]
                layer = (data >= lo) & (data <= hi)
            else:
                lo_l, hi_l = t[0], t[1]
                layer = (data >= lo_l) & (data <= hi_l)
            mask |= layer

        if invert:
            mask = ~mask

        if zero:
            if gray:
                self._data[~mask] = 0
            else:
                arr16 = self._data.view(np.uint8).reshape(self._h, self._w * 2)
                arr16[~mask] = 0
        else:
            if gray:
                self._data[mask] = 255
                self._data[~mask] = 0
            else:
                white = np.uint16(0xFFFF)
                self._data[mask] = white
                self._data[~mask] = 0

    def erode(self, size: int, threshold: int = 1):
        """腐蚀（简单实现：均值滤波 + 阈值）。"""
        self._morph(size, threshold, "erode")

    def dilate(self, size: int, threshold: int = 1):
        """膨胀（简单实现：均值滤波 + 阈值）。"""
        self._morph(size, threshold, "dilate")

    def open(self, size: int, threshold: int = 1):
        """开运算：先腐蚀后膨胀。"""
        self.erode(size, threshold)
        self.dilate(size, threshold)

    def close(self, size: int, threshold: int = 1):
        """闭运算：先膨胀后腐蚀。"""
        self.dilate(size, threshold)
        self.erode(size, threshold)

    def _morph(self, size: int, threshold: int, op: str):
        """形态学操作（基于均值滤波的近似）。"""
        if self._fmt == self.GRAYSCALE:
            data = self._data.astype(np.float32)
        else:
            data = self.to_grayscale()._data.astype(np.float32)

        kernel = np.ones((size, size), np.float32) / (size * size)
        from scipy import ndimage as _nd  # noqa: will be re-checked below

        try:
            import scipy.ndimage as ndi
            if op == "erode":
                filtered = ndi.minimum_filter(data, size=size)
            else:
                filtered = ndi.maximum_filter(data, size=size)
        except ImportError:
            # fallback: 均值近似
            filtered = _simple_convolve(data, kernel)
            threshold = 128

        if self._fmt == self.GRAYSCALE:
            self._data = filtered.astype(np.uint8)
        else:
            # 更新 RGB565 的亮度近似
            r = ((self._data >> 11) & 0x1F).astype(np.uint8)
            g = ((self._data >> 5) & 0x3F).astype(np.uint8)
            b = (self._data & 0x1F).astype(np.uint8)
            gray_new = 0.299 * r + 0.587 * g + 0.114 * b
            self._data = (np.uint16(r) << 11) | (np.uint16(g) << 5) | np.uint16(b)

    def histogram(self, bins: int = 255):
        """计算直方图。"""
        if self._fmt == self.GRAYSCALE:
            data = self._data.ravel()
        else:
            data = self.to_grayscale()._data.ravel()
        hist, _ = np.histogram(data, bins=bins, range=(0, 255))
        return hist

    def get_statistics(self, thresholds=None, roi=None):
        """获取图像统计信息。

        Returns:
            dict: mean, std, min, max
        """
        data = self._data if roi is None else self._apply_roi(roi)
        if self._fmt == self.GRAYSCALE:
            return {
                "mean": float(data.mean()),
                "std": float(data.std()),
                "min": int(data.min()),
                "max": int(data.max()),
            }
        gray = self.to_grayscale()._data
        if roi is not None:
            gray = self._apply_roi(roi)
        return {
            "mean": float(gray.mean()),
            "std": float(gray.std()),
            "min": int(gray.min()),
            "max": int(gray.max()),
        }

    def gamma_correction(self, gamma: float = 1.0, contrast: float = 1.0, brightness: float = 0.0):
        """伽马校正 + 对比度/亮度调整。"""
        if self._fmt == self.GRAYSCALE:
            data = self._data.astype(np.float32)
            data = ((data / 255.0) ** gamma) * 255.0 * contrast + brightness
            self._data = np.clip(data, 0, 255).astype(np.uint8)
        else:
            rgb = self.to_rgb888().astype(np.float32)
            rgb = ((rgb / 255.0) ** gamma) * 255.0 * contrast + brightness
            rgb = np.clip(rgb, 0, 255).astype(np.uint8)
            r, g, b = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
            self._data = (np.uint16(r >> 3) << 11) | (np.uint16(g >> 2) << 5) | np.uint16(b >> 3)

    def bilateral(self, color_sigma: float = 0.1, space_sigma: float = 1):
        """双边滤波（使用 PIL 近似）。"""
        pil = self._to_pil()
        pil = pil.filter(PILImageFilter.SMOOTH)
        if self._fmt == self.GRAYSCALE:
            self._data = np.array(pil, dtype=np.uint8)
        else:
            rgb = np.array(pil, dtype=np.uint8)
            r, g, b = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
            self._data = (np.uint16(r >> 3) << 11) | (np.uint16(g >> 2) << 5) | np.uint16(b >> 3)

    # ── 特征检测 ──

    def find_blobs(
        self, thresholds, roi=None,
        x_stride: int = 2, y_stride: int = 1,
        invert: bool = False,
        area_threshold: int = 10, pixels_threshold: int = 10,
        merge: bool = False, margin: int = 0,
        threshold_cb=None, merge_cb=None,
    ):
        """颜色追踪——核心功能。

        基于 OpenCV 语义的纯 numpy 实现：
        1. inRange() 逐 threshold 掩码
        2. 连通域分析（两遍扫描）
        3. Blob 格式封装

        Args:
            thresholds: 阈值列表 [(L_min, L_max)] 或 [(L_min, L_max, A_min, A_max, B_min, B_max)]
            roi: (x, y, w, h) 感兴趣区域
            invert: 反转掩码
            merge: 是否合并邻近 blob
            margin: 合并间距

        Returns:
            BlobList
        """
        import numpy as np

        stride_slice = (slice(None, None, y_stride), slice(None, None, x_stride))

        data = self._data
        if roi is not None:
            rx, ry, rw, rh = roi
            data = self._data[ry:ry + rh, rx:rx + rw]

        is_gray = (self._fmt == self.GRAYSCALE)

        # 逐阈值生成掩码
        combined_mask = np.zeros(data.shape[:1] if is_gray else data.shape, dtype=bool)
        for t in thresholds:
            lo, hi = t[0], t[1]
            if is_gray:
                layer = (data >= lo) & (data <= hi)
            else:
                gray_data = self.to_grayscale()._data
                if roi is not None:
                    gray_data = gray_data[ry:ry + rh, rx:rx + rw]
                layer = (gray_data >= lo) & (gray_data <= hi)
            combined_mask |= layer

        if invert:
            combined_mask = ~combined_mask

        # 步进降采样
        combined_mask = combined_mask[stride_slice]

        # 连通域标记（两遍扫描）
        labels, num_features = _connected_components(combined_mask)

        if num_features == 0:
            return BlobList([])

        # 提取 Blob
        blobs = []
        for label_id in range(1, num_features + 1):
            ys, xs = np.where(labels == label_id)
            if len(xs) < pixels_threshold:
                continue

            x_min, x_max = int(xs.min()), int(xs.max())
            y_min, y_max = int(ys.min()), int(ys.max())
            area = (x_max - x_min + 1) * (y_max - y_min + 1)
            if area < area_threshold:
                continue

            # 恢复 ROI 偏移 + stride
            if roi is not None:
                rx, ry, _, _ = roi
                offset_x = rx + x_min * x_stride
                offset_y = ry + y_min * y_stride
            else:
                offset_x = x_min * x_stride
                offset_y = y_min * y_stride

            w_blob = (x_max - x_min + 1) * x_stride
            h_blob = (y_max - y_min + 1) * y_stride
            pixels = len(xs)

            blobs.append(Blob(offset_x, offset_y, w_blob, h_blob,
                              pixels=pixels, code=label_id))

        # Merge
        if merge and len(blobs) > 1:
            blobs = _merge_blobs(blobs, margin, merge_cb)

        return BlobList(blobs)

    # ── ROI 工具 ──

    def _apply_roi(self, roi):
        """裁剪 ROI。"""
        x, y, w, h = roi
        return self._data[y:y + h, x:x + w]

    @staticmethod
    def _remap_roi(results, roi):
        """将 ROI 内坐标映射回原图坐标。"""
        if roi is None:
            return results
        rx, ry, _, _ = roi
        for r in results:
            r._x += rx
            r._y += ry
        return results

    # ── Phase 3: 高级特征检测 ──

    def find_apriltags(self, roi=None, families=None,
                       fx=None, fy=None):
        """AprilTag 检测。

        使用 dt_apriltags 库。若库不可用则返回空列表。

        Args:
            roi: (x, y, w, h) 感兴趣区域
            families: 标签族，如 "tag36h11"
            fx, fy: 相机焦距（未使用，为 API 兼容保留）

        Returns:
            AprilTagList
        """
        try:
            from dt_apriltags import Detector as _AprilDetector
        except ImportError:
            return AprilTagList([])

        data = self._get_gray_data(roi)
        gray = (data * 255).astype(np.uint8) if data.dtype != np.uint8 else data

        try:
            det = _AprilDetector(families=families or "tag36h11")
            tags = det.detect(gray)
        except Exception:
            return AprilTagList([])

        results = []
        for t in tags:
            tag_family = t.tag_family if hasattr(t, 'tag_family') else "tag36h11"
            tag_id = t.tag_id if hasattr(t, 'tag_id') else 0
            center = t.center if hasattr(t, 'center') else (0.0, 0.0)
            corners = t.corners if hasattr(t, 'corners') else None
            hamming = t.hamming if hasattr(t, 'hamming') else 0
            decision_margin = t.decision_margin if hasattr(t, 'decision_margin') else 0.0

            if corners is not None:
                x_min = int(min(c[0] for c in corners))
                y_min = int(min(c[1] for c in corners))
                x_max = int(max(c[0] for c in corners))
                y_max = int(max(c[1] for c in corners))
            else:
                x_min = y_min = 0
                x_max = y_max = 0

            results.append(AprilTag(
                x=x_min, y=y_min, w=x_max - x_min, h=y_max - y_min,
                id=tag_id, family=tag_family,
                cx=int(center[0]), cy=int(center[1]),
                rotation=0.0,
                corners=corners.tolist() if corners is not None else None,
                hamming=hamming, decision_margin=decision_margin,
            ))

        self._remap_roi(results, roi)
        return AprilTagList(results)

    def find_qrcodes(self, roi=None):
        """二维码检测。

        使用 pyzbar 库。若库不可用则返回空列表。

        Returns:
            QRCodeList
        """
        try:
            from pyzbar import pyzbar as _pyzbar
        except ImportError:
            return QRCodeList([])

        rgb = self.to_rgb888()
        if roi is not None:
            rx, ry, rw, rh = roi
            rgb = rgb[ry:ry + rh, rx:rx + rw]

        try:
            decoded = _pyzbar.decode(rgb)
        except Exception:
            return QRCodeList([])

        results = [
            QRCode(
                x=r.rect.left, y=r.rect.top,
                w=r.rect.width, h=r.rect.height,
                payload=r.data.decode("utf-8", errors="replace") if r.data else "",
                type=str(r.type),
                corners=[(p.x, p.y) for p in r.polygon] if r.polygon else None,
            )
            for r in decoded if r.type == "QRCODE"
        ]

        self._remap_roi(results, roi)
        return QRCodeList(results)

    def find_barcodes(self, roi=None):
        """条形码检测。

        使用 pyzbar 库。若库不可用则返回空列表。

        Returns:
            BarcodeList
        """
        try:
            from pyzbar import pyzbar as _pyzbar
        except ImportError:
            return BarcodeList([])

        rgb = self.to_rgb888()
        if roi is not None:
            rx, ry, rw, rh = roi
            rgb = rgb[ry:ry + rh, rx:rx + rw]

        try:
            decoded = _pyzbar.decode(rgb)
        except Exception:
            return BarcodeList([])

        results = [
            Barcode(
                x=r.rect.left, y=r.rect.top,
                w=r.rect.width, h=r.rect.height,
                payload=r.data.decode("utf-8", errors="replace") if r.data else "",
                type=str(r.type),
                corners=[(p.x, p.y) for p in r.polygon] if r.polygon else None,
            )
            for r in decoded if r.type != "QRCODE"
        ]

        self._remap_roi(results, roi)
        return BarcodeList(results)

    def find_features(self, cascade, roi=None):
        """Haar 级联特征检测。

        Args:
            cascade: HaarCascade 实例
            roi: (x, y, w, h)

        Returns:
            FeatureList
        """
        return cascade.detect(self, roi)

    def find_edges(self, edge_type, threshold):
        """边缘检测。

        Args:
            edge_type: Image.EDGE_CANNY 或 Image.EDGE_SIMPLE
            threshold: 阈值（Canny 用 (low, high)，SIMPLE 用单阈值）

        Returns:
            Image 对象（二值边缘图）
        """
        gray = self._get_gray_data()
        gray = (gray * 255).astype(np.uint8) if gray.dtype != np.uint8 else gray

        if edge_type == getattr(Image, 'EDGE_CANNY', 1):
            from scipy import ndimage as _ndi
            low, high = threshold if isinstance(threshold, (list, tuple)) else (threshold, threshold * 3)
            # Sobel
            grad_x = _ndi.sobel(gray.astype(float), axis=1)
            grad_y = _ndi.sobel(gray.astype(float), axis=0)
            mag = np.hypot(grad_x, grad_y)
            mag = np.clip(mag / mag.max() * 255, 0, 255).astype(np.uint8)
            # 双阈值
            strong = 255
            weak = 75
            edges = np.zeros_like(mag)
            edges[mag >= high] = strong
            edges[(mag >= low) & (mag < high)] = weak
            # 边缘跟踪（简化）
            result = edges
        else:
            # Simple: Sobel 幅值 → 阈值
            from scipy import ndimage as _ndi
            grad_x = _ndi.sobel(gray.astype(float), axis=1)
            grad_y = _ndi.sobel(gray.astype(float), axis=0)
            mag = np.hypot(grad_x, grad_y)
            result = (mag > threshold).astype(np.uint8) * 255

        if self._fmt == self.GRAYSCALE:
            self._data = result
        else:
            # RGB565: 写入灰度等价
            gray_result = result.astype(np.uint8)
            g = gray_result.astype(np.uint16)
            self._data = ((g >> 3) << 11) | ((g >> 2) << 5) | (g >> 3)
        return self

    def find_lines(self, roi=None, x_stride=2, y_stride=1,
                   threshold=1000, theta_margin=25, rho_margin=25):
        """Hough 直线检测。

        Returns:
            LineList
        """
        gray = self._get_gray_data(roi)
        gray = (gray * 255).astype(np.uint8) if gray.dtype != np.uint8 else gray

        # Sobel 边缘
        from scipy import ndimage as _ndi
        grad_x = _ndi.sobel(gray.astype(float), axis=1)
        grad_y = _ndi.sobel(gray.astype(float), axis=0)
        mag = np.hypot(grad_x, grad_y)
        edges = (mag > 50).astype(np.uint8)

        # Hough 变换
        thetas = np.deg2rad(np.arange(-90, 90, theta_margin))
        h, w = edges.shape
        diag = int(np.sqrt(h * h + w * w))
        rhos = np.arange(-diag, diag, rho_margin)

        cos_t = np.cos(thetas)
        sin_t = np.sin(thetas)

        accumulator = np.zeros((len(rhos), len(thetas)), dtype=np.int32)
        edge_ys, edge_xs = np.nonzero(edges)

        for i in range(0, len(edge_xs), x_stride):
            x = edge_xs[i]
            y = edge_ys[i]
            for t_idx in range(len(thetas)):
                rho = x * cos_t[t_idx] + y * sin_t[t_idx]
                r_idx = np.argmin(np.abs(rhos - rho))
                accumulator[r_idx, t_idx] += 1

        # 提取峰值
        lines = []
        for r_idx in range(len(rhos)):
            for t_idx in range(len(thetas)):
                vote = int(accumulator[r_idx, t_idx])
                if vote >= threshold:
                    rho = float(rhos[r_idx])
                    theta_val = float(thetas[t_idx])
                    lines.append((rho, theta_val, vote))

        # 非极大值抑制
        lines = _nms_lines(lines, theta_margin * 2, rho_margin * 2, threshold)

        results = []
        for rho, theta_val, mag in lines:
            a = np.cos(theta_val)
            b = np.sin(theta_val)
            x0 = a * rho
            y0 = b * rho
            x1 = int(x0 + 1000 * (-b))
            y1 = int(y0 + 1000 * a)
            x2 = int(x0 - 1000 * (-b))
            y2 = int(y0 - 1000 * a)
            line = Line(x1=x1, y1=y1, x2=x2, y2=y2,
                        rho=rho, theta=theta_val,
                        magnitude=mag)
            results.append(line)

        self._remap_roi(results, roi)
        return LineList(results)

    def find_circles(self, roi=None, x_stride=2, y_stride=1,
                     threshold=2000, x_margin=10, y_margin=10,
                     r_margin=10, r_min=2, r_max=100, r_step=2):
        """Hough 圆检测。

        Returns:
            CircleList
        """
        gray = self._get_gray_data(roi)
        gray = (gray * 255).astype(np.uint8) if gray.dtype != np.uint8 else gray

        # Sobel 边缘
        from scipy import ndimage as _ndi
        grad_x = _ndi.sobel(gray.astype(float), axis=1)
        grad_y = _ndi.sobel(gray.astype(float), axis=0)
        mag = np.hypot(grad_x, grad_y)
        edges = (mag > 50).astype(np.uint8)

        h, w = edges.shape
        edge_ys, edge_xs = np.nonzero(edges)

        # 3D Hough 累加器
        radii = np.arange(r_min, r_max, r_step)
        acc = np.zeros((len(radii), h, w), dtype=np.int32)

        for i in range(0, len(edge_xs), x_stride):
            x, y = edge_xs[i], edge_ys[i]
            for ri, r in enumerate(radii):
                for theta_idx in range(0, 360, y_stride * 15):
                    ang = np.deg2rad(theta_idx)
                    cx = int(x - r * np.cos(ang))
                    cy = int(y - r * np.sin(ang))
                    if 0 <= cx < w and 0 <= cy < h:
                        acc[ri, cy, cx] += 1

        # 提取峰值
        circles = []
        for ri in range(len(radii)):
            for cy in range(h):
                for cx in range(w):
                    if acc[ri, cy, cx] >= threshold:
                        circles.append((cx, cy, radii[ri], int(acc[ri, cy, cx])))

        # 非极大值抑制
        circles = _nms_circles(circles, x_margin, y_margin, r_margin, threshold)

        results = [Circle(x=c[0], y=c[1], r=c[2], magnitude=c[3]) for c in circles]
        self._remap_roi(results, roi)
        return CircleList(results)

    def find_rects(self, roi=None, threshold=10000):
        """矩形检测（基于连通域外接矩形）。

        Returns:
            RectList
        """
        gray = self._get_gray_data(roi)
        gray = (gray * 255).astype(np.uint8) if gray.dtype != np.uint8 else gray

        # 简单二值化
        binary = (gray > 128).astype(np.uint8)

        # 连通域
        from scipy import ndimage as _ndi
        labeled, num = _ndi.label(binary)

        results = []
        for label_id in range(1, num + 1):
            ys, xs = np.where(labeled == label_id)
            if len(xs) == 0:
                continue
            x_min, x_max = int(xs.min()), int(xs.max())
            y_min, y_max = int(ys.min()), int(ys.max())
            w = x_max - x_min + 1
            h = y_max - y_min + 1
            area = w * h
            if area < threshold:
                continue
            # 矩形度（面积 / 外接矩形面积）
            rect_area = w * h
            pixel_count = len(xs)
            rect_degree = pixel_count / rect_area if rect_area > 0 else 0
            if rect_degree < 0.4:
                continue  # 不够矩形
            results.append(Rect(x=x_min, y=y_min, w=w, h=h,
                                magnitude=rect_area))

        self._remap_roi(results, roi)
        return RectList(results)

    # ── 工具 ──

    def _get_gray_data(self, roi=None):
        """获取灰度 ndarray (H, W) uint8"""
        if self._fmt == self.GRAYSCALE:
            data = self._data
        else:
            data = self.to_grayscale()._data
        if roi is not None:
            x, y, w, h = roi
            data = data[y:y + h, x:x + w]
        return data

    def __repr__(self):
        return f"<Image {self._w}x{self._h} fmt={'RGB565' if self._fmt == self.RGB565 else 'GRAYSCALE'}>"


# ── Blob / BlobList ──

class Blob:
    """OpenMV Blob 兼容数据类型。"""

    def __init__(self, x, y, w, h, pixels=0, corners=None, code=-1, rotation=0.0):
        self._x = x
        self._y = y
        self._w = w
        self._h = h
        self._pixels = pixels
        self._code = code
        self._rotation = rotation

    def cx(self):
        return self._x + self._w // 2

    def cy(self):
        return self._y + self._h // 2

    def x(self):
        return self._x

    def y(self):
        return self._y

    def w(self):
        return self._w

    def h(self):
        return self._h

    def area(self):
        return self._w * self._h

    def rotation(self):
        return self._rotation

    def pixels(self):
        return self._pixels

    def rect(self):
        return (self._x, self._y, self._w, self._h)

    def corners(self):
        return [
            (self._x, self._y),
            (self._x + self._w, self._y),
            (self._x + self._w, self._y + self._h),
            (self._x, self._y + self._h),
        ]

    def code(self):
        return self._code

    def __repr__(self):
        return f"<Blob ({self._x},{self._y}) {self._w}x{self._h} area={self.area()}>"


class BlobList:
    """OpenMV 兼容的 Blob 列表，支持索引和 len()。"""

    def __init__(self, blobs):
        self._blobs = list(blobs)

    def __getitem__(self, i):
        return self._blobs[i]

    def __len__(self):
        return len(self._blobs)

    def __iter__(self):
        return iter(self._blobs)

    def __repr__(self):
        return f"<BlobList count={len(self._blobs)}>"


# ── 内部工具 ──

def _connected_components(binary: np.ndarray) -> tuple[np.ndarray, int]:
    """两遍扫描连通域标记算法。

    Args:
        binary: 二值图像 (H, W) dtype=bool

    Returns:
        (labels, num_features): 标记数组（0=背景，1~N=前景）和特征数
    """
    h, w = binary.shape
    labels = np.zeros((h, w), dtype=np.int32)
    label = 0
    equivalences = []

    # First pass
    for y in range(h):
        for x in range(w):
            if not binary[y, x]:
                continue

            # 检查左邻和上邻
            left = labels[y, x - 1] if x > 0 else 0
            top = labels[y - 1, x] if y > 0 else 0

            if left == 0 and top == 0:
                label += 1
                labels[y, x] = label
                equivalences.append({label})
            elif left != 0 and top == 0:
                labels[y, x] = left
            elif left == 0 and top != 0:
                labels[y, x] = top
            elif left == top:
                labels[y, x] = left
            else:
                labels[y, x] = min(left, top)
                # 合并等价类
                eq_set = equivalences[left - 1]
                eq_set.update(equivalences[top - 1])
                for v in equivalences[top - 1]:
                    if v <= len(equivalences):
                        equivalences[v - 1] = eq_set
                for v in equivalences[left - 1]:
                    if v <= len(equivalences):
                        equivalences[v - 1] = eq_set

    # Second pass: 重映射
    remap = {}
    next_label = 1
    for y in range(h):
        for x in range(w):
            if labels[y, x] == 0:
                continue
            orig = labels[y, x]
            if orig in remap:
                labels[y, x] = remap[orig]
            else:
                remap[orig] = next_label
                labels[y, x] = next_label
                next_label += 1

    return labels, next_label - 1


def _merge_blobs(blobs, margin, merge_cb=None):
    """贪心合并 blob，间距 ≤ margin 时合并。"""
    merged = list(blobs)
    changed = True
    while changed:
        changed = False
        new_list = []
        used = [False] * len(merged)
        for i, a in enumerate(merged):
            if used[i]:
                continue
            best_j = -1
            best_dist = float("inf")
            for j in range(i + 1, len(merged)):
                if used[j]:
                    continue
                b = merged[j]
                gap_x = max(0, abs(a.cx() - b.cx()) - (a.w() + b.w()) // 2)
                gap_y = max(0, abs(a.cy() - b.cy()) - (a.h() + b.h()) // 2)
                dist = gap_x + gap_y
                if dist <= margin and dist < best_dist:
                    best_j = j
                    best_dist = dist

            if best_j >= 0:
                b = merged[best_j]
                x1 = min(a.x(), b.x())
                y1 = min(a.y(), b.y())
                x2 = max(a.x() + a.w(), b.x() + b.w())
                y2 = max(a.y() + a.h(), b.y() + b.h())
                new_blob = Blob(x1, y1, x2 - x1, y2 - y1,
                                pixels=a.pixels() + b.pixels(),
                                code=a.code())
                if merge_cb and not merge_cb(a, b):
                    new_list.extend([a, b])
                else:
                    new_list.append(new_blob)
                    changed = True
                used[i] = True
                used[best_j] = True
            else:
                new_list.append(a)
                used[i] = True

        # 添加未使用的
        for i, u in enumerate(used):
            if not u:
                new_list.append(merged[i])

        merged = new_list

    return merged


def _simple_convolve(data: np.ndarray, kernel: np.ndarray) -> np.ndarray:
    """简单 2D 卷积（无 scipy fallback）。"""
    h, w = data.shape
    kh, kw = kernel.shape
    pad_h, pad_w = kh // 2, kw // 2
    padded = np.pad(data, ((pad_h, pad_h), (pad_w, pad_w)), mode="edge")
    result = np.zeros_like(data)
    for i in range(h):
        for j in range(w):
            result[i, j] = np.sum(padded[i:i + kh, j:j + kw] * kernel)
    return result


# ── Phase 3 返回值类型 ──

class _DetectResult:
    """检测结果基类。"""

    def __init__(self, x=0, y=0, w=0, h=0):
        self._x = x
        self._y = y
        self._w = w
        self._h = h

    def x(self):
        return self._x

    def y(self):
        return self._y

    def w(self):
        return self._w

    def h(self):
        return self._h

    def rect(self):
        return (self._x, self._y, self._w, self._h)

    def area(self):
        return self._w * self._h

    def cx(self):
        return self._x + self._w // 2

    def cy(self):
        return self._y + self._h // 2


class _DetectResultList:
    """检测结果列表基类。"""

    def __init__(self, items):
        self._items = list(items)

    def __getitem__(self, i):
        return self._items[i]

    def __len__(self):
        return len(self._items)

    def __iter__(self):
        return iter(self._items)

    def count(self):
        return len(self._items)


class AprilTag(_DetectResult):
    """AprilTag 检测结果。"""

    def __init__(self, x=0, y=0, w=0, h=0, id=0, family="tag36h11",
                 cx=0, cy=0, rotation=0.0, corners=None,
                 hamming=0, decision_margin=0.0):
        super().__init__(x, y, w, h)
        self._id = id
        self._family = family
        self._cx = cx
        self._cy = cy
        self._rotation = rotation
        self._corners = corners
        self._hamming = hamming
        self._decision_margin = decision_margin

    def id(self):
        return self._id

    def family(self):
        return self._family

    def rotation(self):
        return self._rotation

    def corners(self):
        return self._corners

    def hamming(self):
        return self._hamming

    def decision_margin(self):
        return self._decision_margin

    def __repr__(self):
        return f"<AprilTag id={self._id} family={self._family} ({self._cx},{self._cy})>"


class AprilTagList(_DetectResultList):
    def __repr__(self):
        return f"<AprilTagList count={len(self._items)}>"


class QRCode(_DetectResult):
    """二维码检测结果。"""

    def __init__(self, x=0, y=0, w=0, h=0, payload="", type="QRCODE",
                 corners=None):
        super().__init__(x, y, w, h)
        self._payload = payload
        self._type = type
        self._corners = corners

    def payload(self):
        return self._payload

    def type(self):
        return self._type

    def corners(self):
        return self._corners

    def __repr__(self):
        return f"<QRCode payload={self._payload[:20]}>"


class QRCodeList(_DetectResultList):
    def __repr__(self):
        return f"<QRCodeList count={len(self._items)}>"


class Barcode(_DetectResult):
    """条形码检测结果。"""

    def __init__(self, x=0, y=0, w=0, h=0, payload="", type="",
                 corners=None):
        super().__init__(x, y, w, h)
        self._payload = payload
        self._type = type
        self._corners = corners

    def payload(self):
        return self._payload

    def type(self):
        return self._type

    def corners(self):
        return self._corners

    def __repr__(self):
        return f"<Barcode type={self._type} payload={self._payload[:20]}>"


class BarcodeList(_DetectResultList):
    def __repr__(self):
        return f"<BarcodeList count={len(self._items)}>"


class Feature(_DetectResult):
    """Haar 级联特征检测结果。"""

    def __init__(self, x=0, y=0, w=0, h=0, rotation=0.0):
        super().__init__(x, y, w, h)
        self._rotation = rotation

    def rotation(self):
        return self._rotation

    def __repr__(self):
        return f"<Feature ({self._x},{self._y}) {self._w}x{self._h}>"


class FeatureList(_DetectResultList):
    def __repr__(self):
        return f"<FeatureList count={len(self._items)}>"


class Line(_DetectResult):
    """直线检测结果。"""

    def __init__(self, x1=0, y1=0, x2=0, y2=0, rho=0.0, theta=0.0,
                 magnitude=0):
        super().__init__(min(x1, x2), min(y1, y2),
                         abs(x2 - x1), abs(y2 - y1))
        self._x1 = x1
        self._y1 = y1
        self._x2 = x2
        self._y2 = y2
        self._rho = rho
        self._theta = theta
        self._magnitude = magnitude

    def x1(self):
        return self._x1

    def y1(self):
        return self._y1

    def x2(self):
        return self._x2

    def y2(self):
        return self._y2

    def rho(self):
        return self._rho

    def theta(self):
        return self._theta

    def magnitude(self):
        return self._magnitude

    def line(self):
        return (self._x1, self._y1, self._x2, self._y2)

    def __repr__(self):
        return f"<Line rho={self._rho:.1f} theta={self._theta:.1f}>"


class LineList(_DetectResultList):
    def __repr__(self):
        return f"<LineList count={len(self._items)}>"


class Circle(_DetectResult):
    """圆检测结果。"""

    def __init__(self, x=0, y=0, r=0, magnitude=0):
        super().__init__(x - r, y - r, r * 2, r * 2)
        self._cx = x
        self._cy = y
        self._r = r
        self._magnitude = magnitude

    def x(self):
        return self._cx

    def y(self):
        return self._cy

    def r(self):
        return self._r

    def magnitude(self):
        return self._magnitude

    def __repr__(self):
        return f"<Circle ({self._cx},{self._cy}) r={self._r}>"


class CircleList(_DetectResultList):
    def __repr__(self):
        return f"<CircleList count={len(self._items)}>"


class Rect(_DetectResult):
    """矩形检测结果。"""

    def __init__(self, x=0, y=0, w=0, h=0, magnitude=0):
        super().__init__(x, y, w, h)
        self._magnitude = magnitude

    def magnitude(self):
        return self._magnitude

    def __repr__(self):
        return f"<Rect ({self._x},{self._y}) {self._w}x{self._h}>"


class RectList(_DetectResultList):
    def __repr__(self):
        return f"<RectList count={len(self._items)}>"


# ── NMS 工具 ──

def _nms_lines(lines, theta_window, rho_window, min_score):
    """直线非极大值抑制。"""
    if not lines:
        return []
    lines = sorted(set(lines), key=lambda x: -x[2])  # 按 magnitude 降序
    kept = []
    for l in lines:
        rho, theta, mag = l
        duplicate = False
        for k in kept:
            kr, kt, kmag = k
            if abs(rho - kr) <= rho_window and abs(theta - kt) <= theta_window:
                duplicate = True
                break
        if not duplicate:
            kept.append(l)
    return kept


def _nms_circles(circles, x_margin, y_margin, r_margin, min_score):
    """圆非极大值抑制。"""
    if not circles:
        return []
    circles = sorted(circles, key=lambda c: -c[3])  # 按 magnitude 降序
    kept = []
    for c in circles:
        cx, cy, r, mag = c
        duplicate = False
        for k in kept:
            kx, ky, kr, kmag = k
            if (abs(cx - kx) <= x_margin and
                    abs(cy - ky) <= y_margin and
                    abs(r - kr) <= r_margin):
                duplicate = True
                break
        if not duplicate:
            kept.append(c)
    return kept
