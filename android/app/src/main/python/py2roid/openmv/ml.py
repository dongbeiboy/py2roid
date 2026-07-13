"""
OpenMV ml 模块兼容实现。

底层通过 PythonBridge → Kotlin Detector 调用 ONNX / TFLite 引擎。
"""

from __future__ import annotations

import json
from typing import Optional


class HaarCascade:
    """OpenMV HaarCascade 兼容实现。

    使用 scipy.ndimage 做简单特征检测近似。
    实际级联检测需要 OpenCV 的 haarcascades，当前为简化实现。
    """

    _CASCADE_MAP = {
        "frontalface": None,
        "eye": None,
        "smile": None,
    }

    def __init__(self, cascade_name: str):
        self._name = cascade_name

    def detect(self, image, roi=None) -> list:
        """执行特征检测。

        当前实现：基于亮度直方图的简单区域分割。
        完整 Haar 级联需要 OpenCV Python 包。

        Returns:
            FeatureList 或空列表
        """
        from .image import Feature, FeatureList, Image as Img

        if isinstance(image, Img):
            gray = image._get_gray_data(roi)
        else:
            gray = image
        if roi is not None:
            rx, ry, _, _ = roi
        else:
            rx = ry = 0

        h, w = gray.shape
        if h < 24 or w < 24:
            return FeatureList([])

        # 简单：基于局部方差检测类脸区域
        from scipy import ndimage as ndi
        local_std = ndi.uniform_filter(gray.astype(float), size=12)
        local_mean = ndi.uniform_filter(gray.astype(float), size=24)
        diff = np.abs(gray.astype(float) - local_mean)
        candidate = (diff > 30).astype(np.uint8)

        # 连通域
        labeled, num = ndi.label(candidate)
        results = []
        for label_id in range(1, num + 1):
            ys, xs = np.where(labeled == label_id)
            if len(xs) < 50:
                continue
            x_min, x_max = int(xs.min()), int(xs.max())
            y_min, y_max = int(ys.min()), int(ys.max())
            fw, fh = x_max - x_min, y_max - y_min
            if fw < 20 or fh < 20 or fw > w * 0.8 or fh > h * 0.8:
                continue
            results.append(Feature(
                x=x_min + rx, y=y_min + ry, w=fw, h=fh
            ))

        return FeatureList(results)


class Predictions:
    """ml.predict() 的返回类型包装。

    兼容 OpenMV Predictions 接口。
    """

    def __init__(self, boxes=None, scores=None, class_ids=None):
        self._boxes = boxes or []
        self._scores = scores or []
        self._class_ids = class_ids or []

    @property
    def count(self):
        return len(self._boxes)

    def output(self, index=0):
        """返回第 index 个结果的类别 ID。"""
        return self._class_ids[index] if index < len(self._class_ids) else None

    def rect(self, index=0):
        """返回第 index 个结果的检测框 (x, y, w, h)。"""
        return self._boxes[index] if index < len(self._boxes) else None

    def scores(self):
        """返回所有置信度列表。"""
        return self._scores

    def __len__(self):
        return len(self._boxes)

    def __repr__(self):
        return f"<Predictions count={self.count}>"


class ML:
    """OpenMV ml 模块兼容层。

    通过 PythonBridge → Kotlin Detector 运行 ONNX / TFLite 推理。
    """

    def __init__(self):
        self._loaded_model = None
        self._loaded_path = None

    def load(self, path: str):
        """加载模型。

        Args:
            path: 模型文件名（在模型目录中查找）

        Returns:
            模型句柄（当前返回路径字符串）
        """
        self._loaded_path = path
        self._loaded_model = path
        return self._loaded_model

    def predict(
        self,
        input_data,
        model: Optional[str] = None,
        *,
        model_path: Optional[str] = None,
        backend: Optional[str] = None,
    ) -> Predictions:
        """运行模型推理。

        Args:
            input_data: Image 对象或 numpy 数组
            model: 模型标识（已加载的模型）
            model_path: 模型文件路径（未加载时使用）
            backend: "ONNX" / "TFLITE" / "AUTO"

        Returns:
            Predictions 对象
        """
        from ._internal import bridge as _bridge

        path = model_path or self._loaded_path
        if not path:
            raise ValueError("No model loaded. Call ml.load() first.")

        bk = backend or "AUTO"

        # 预处理输入：Image → RGB bytes
        if hasattr(input_data, "to_rgb888"):
            rgb_data = input_data.to_rgb888().tobytes()
        elif hasattr(input_data, "tobytes"):
            rgb_data = input_data.tobytes()
        else:
            rgb_data = bytes(input_data)

        result_json = _bridge.ml_predict(rgb_data, path, bk)
        if not result_json:
            return Predictions()

        try:
            result = json.loads(result_json)
        except (json.JSONDecodeError, TypeError):
            return Predictions()

        boxes = []
        scores = []
        class_ids = []

        for det in result.get("detections", []):
            x1 = det.get("x1", 0)
            y1 = det.get("y1", 0)
            x2 = det.get("x2", 0)
            y2 = det.get("y2", 0)
            w = x2 - x1
            h = y2 - y1
            boxes.append((int(x1), int(y1), int(w), int(h)))
            scores.append(det.get("confidence", 0))
            class_ids.append(det.get("classId", -1))

        return Predictions(boxes=boxes, scores=scores, class_ids=class_ids)


# 全局单例
ml = ML()
