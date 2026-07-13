"""
OpenMV machine.Pin 兼容实现（Stub）。

手机没有 GPIO，所有操作均为空操作。
"""


class Pin:
    """Stub 实现 — 手机没有 GPIO 引脚。"""

    OUT = 0
    IN = 1
    OPEN_DRAIN = 2
    PULL_UP = 3
    PULL_DOWN = 4

    def __init__(self, id, mode=OUT, pull=None, value=None):
        self._id = id
        self._mode = mode
        self._value = value

    def value(self, val=None):
        if val is not None:
            self._value = val
        return self._value

    def high(self):
        self._value = 1

    def low(self):
        self._value = 0

    def deinit(self):
        pass
