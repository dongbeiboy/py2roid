"""
OpenMV ml 模块兼容实现。

底层通过 PythonBridge → Kotlin Detector 调用 ONNX / TFLite 引擎。
"""

from __future__ import annotations

import json
from typing import Optional


class HaarCascade:
    """OpenMV HaarCascade 兼容实现。

    使用 Pillow 的内置级联或简单的占位。实际级联检测需要 OpenCV。
    当前实现为占位，返回空列表。
    """

    _CASCADE_MAP = {
        "frontalface": None,
        "eye": None,
        "smile": None,
    }

    def __init__(self, cascade_name: str):
        self._name = cascade_name

    def detect(self, image, scale_factor=1.1, min_neighbors=3, **kwargs):
        """执行检测（占位实现）。

        Returns:
            [] — 暂未实现，需 OpenCV
        """
        return []


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
