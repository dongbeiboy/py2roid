"""
OpenMV time 模块兼容实现。

底层复用 Python 标准库 time，增加 MicroPython 兼容的
基于 tick 的回绕安全性及可中断的分片 sleep。
"""

from __future__ import annotations

import time as _time

_ticks_start: int = 0


def _init():
    """初始化 ticks 基准点（由 py2roid.openmv.__init__ 调用）。"""
    global _ticks_start
    _ticks_start = _time.monotonic_ns()


def sleep_ms(ms: float):
    """MicroPython 兼容 sleep，50ms 分片确保可中断。

    Args:
        ms: 毫秒数
    """
    from ._internal import interruptible
    remaining = ms
    while remaining > 0:
        interruptible._check_stop()
        chunk = min(50, remaining)
        _time.sleep(chunk / 1000.0)
        remaining -= chunk


def sleep_us(us: int):
    """微秒级 sleep，1ms 分片。"""
    sleep_ms(max(1, us // 1000))


def ticks_ms() -> int:
    """返回自初始化以来的毫秒数，32 位回绕兼容。"""
    ns = _time.monotonic_ns() - _ticks_start
    return (ns // 1_000_000) & 0x7FFFFFFF


def ticks_us() -> int:
    """返回自初始化以来的微秒数，32 位回绕兼容。"""
    ns = _time.monotonic_ns() - _ticks_start
    return (ns // 1_000) & 0x7FFFFFFF


def ticks_diff(t1: int, t2: int) -> int:
    """MicroPython 兼容的 ticks 差值，正确处理 32 位回绕。

    Returns:
        t1 - t2，自动处理回绕
    """
    delta = (t1 - t2) & 0x7FFFFFFF
    if delta & 0x40000000:
        delta -= 0x80000000
    return delta
