"""
OpenCAM 工程文件解析器（预留骨架）

OpenCAM 是 OpenMV 的 IDE 工程格式，本质上是 ZIP 包，
内含 .py 脚本 + .xml 元数据。本项目预留此模块以便
后续从 OpenCAM 工程中提取视觉处理参数（如 ROI、阈值等）。

当前为骨架，接口待定。
"""

from __future__ import annotations


class OpenCamProject:
    """OpenCAM 工程（预留）"""

    def __init__(self, filepath: str):
        self.filepath = filepath
        self.scripts: list[str] = []
        self.metadata: dict = {}

    def load(self) -> bool:
        """加载工程文件（待实现）"""
        raise NotImplementedError("Phase 2 预留，后续实现")


def extract_params(ocam: OpenCamProject) -> dict:
    """从 OpenCAM 工程中提取视觉参数（待实现）"""
    raise NotImplementedError("Phase 2 预留，后续实现")
