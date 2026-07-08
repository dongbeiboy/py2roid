"""
py2roid 工具函数

坐标格式化、字节转换、日志格式化
"""

from __future__ import annotations

import binascii
import time
from typing import Optional


# ── 坐标工具 ─────────────────────────────────────────

def format_coords(
    x1: int, y1: int,
    x2: int, y2: int,
    norm: bool = False,
    precision: int = 2,
) -> str:
    """格式化边界框坐标为可读字符串.

    Args:
        x1, y1, x2, y2: 坐标值
        norm: 是否使用归一化格式 (0.0~1.0)
        precision: 小数位数

    Returns:
        形如 "(x1,y1,x2,y2)" 或 "(0.12,0.34,0.56,0.78)"
    """
    if norm:
        fmt = f"{{:.{precision}f}}"
        parts = [fmt.format(v / 65535.0) for v in (x1, y1, x2, y2)]
    else:
        parts = [str(int(v)) for v in (x1, y1, x2, y2)]
    return f"({','.join(parts)})"


def clamp_coords(
    x1: int, y1: int, x2: int, y2: int,
    width: int, height: int,
) -> tuple[int, int, int, int]:
    """将坐标限定在图像范围内."""
    x1 = max(0, min(x1, width))
    y1 = max(0, min(y1, height))
    x2 = max(0, min(x2, width))
    y2 = max(0, min(y2, height))
    # 确保 x1<=x2, y1<=y2
    if x1 > x2:
        x1, x2 = x2, x1
    if y1 > y2:
        y1, y2 = y2, y1
    return x1, y1, x2, y2


# ── 字节工具 ─────────────────────────────────────────

def bytes_to_hex(data: bytes, max_len: int = 32, sep: str = " ") -> str:
    """字节串 → 可读十六进制字符串.

    >>> bytes_to_hex(b'\\xAA\\x55\\x01')
    'AA 55 01'
    """
    if not data:
        return "(empty)"
    text = binascii.hexlify(data[:max_len]).decode("ascii").upper()
    grouped = sep.join(text[i:i+2] for i in range(0, len(text), 2))
    if len(data) > max_len:
        grouped += f" ... ({len(data)}B total)"
    return grouped


def hex_to_bytes(hex_str: str) -> Optional[bytes]:
    """十六进制字符串 → 字节串（忽略空格/0x/逗号）."""
    try:
        clean = hex_str.replace(" ", "").replace("0x", "").replace(",", "")
        return binascii.unhexlify(clean)
    except (binascii.Error, ValueError):
        return None


def uint16_to_bytes(value: int) -> bytes:
    """uint16 → 大端 2 字节"""
    return value.to_bytes(2, "big", signed=False)


def bytes_to_uint16(data: bytes, offset: int = 0) -> int:
    """大端 2 字节 → uint16"""
    return int.from_bytes(data[offset:offset+2], "big", signed=False)


# ── 日志工具 ─────────────────────────────────────────

_LOG_LEVELS = {"DEBUG": 0, "INFO": 1, "WARN": 2, "ERROR": 3}
_log_level = "DEBUG"


def set_log_level(level: str):
    """设置日志级别: DEBUG/INFO/WARN/ERROR"""
    global _log_level
    if level in _LOG_LEVELS:
        _log_level = level


def format_log(tag: str, message: str, level: str = "INFO") -> str:
    """格式化日志行.

    格式: [HH:MM:SS.mmm] [LEVEL] [tag] message
    """
    if _LOG_LEVELS.get(level, 1) < _LOG_LEVELS.get(_log_level, 1):
        return ""  # 低于当前级别不输出
    t = time.time()
    ms = int((t - int(t)) * 1000)
    ts = time.strftime("%H:%M:%S", time.localtime(t))
    return f"[{ts}.{ms:03d}] [{level}] [{tag}] {message}"


def log_d(tag: str, message: str):
    """DEBUG 日志"""
    line = format_log(tag, message, "DEBUG")
    if line:
        print(line)


def log_i(tag: str, message: str):
    """INFO 日志"""
    line = format_log(tag, message, "INFO")
    if line:
        print(line)


def log_w(tag: str, message: str):
    """WARN 日志"""
    line = format_log(tag, message, "WARN")
    if line:
        print(line)


def log_e(tag: str, message: str):
    """ERROR 日志"""
    line = format_log(tag, message, "ERROR")
    if line:
        print(line)


# ── 目标工具 ─────────────────────────────────────────

def target_summary(targets: list[dict]) -> str:
    """生成检测目标摘要字符串.

    Args:
        targets: decode_detection_payload 的返回结果

    Returns:
        如 "cls=0 conf=0.92 (120,34,560,480)"
    """
    lines = []
    for i, t in enumerate(targets):
        coord_str = format_coords(t["x1"], t["y1"], t["x2"], t["y2"], norm=True)
        lines.append(
            f"  [{i}] cls={t['class_id']} "
            f"conf={t['confidence']:.2f} {coord_str}"
        )
    return "\n".join(lines)
