"""
py2roid MCU 通讯协议层

帧格式:
┌────────┬────────┬──────────┬──────────┬──────────┬──────────┐
│ 0xAA   │ 0x55   │ Length   │ Cmd      │ Payload  │ Checksum │
│ (1B)   │ (1B)   │ (2B BE)  │ (1B)     │ (N B)    │ (1B)     │
└────────┴────────┴──────────┴──────────┴──────────┴──────────┘

- Length: 负载长度 (Cmd + Payload), 大端 2 字节
- Checksum: Length + Cmd + Payload 累加和, 取低 8 位
"""

from __future__ import annotations

import struct
from typing import Optional

# ── 帧标记 ─────────────────────────────────────────────
FRAME_HEAD = b"\xAA\x55"
HEAD_LEN = 2
LENGTH_LEN = 2
CMD_LEN = 1
CHECKSUM_LEN = 1
MIN_FRAME_LEN = HEAD_LEN + LENGTH_LEN + CMD_LEN + CHECKSUM_LEN  # 6

# ── 命令字定义 ─────────────────────────────────────────
CMD_DETECTION_RESULT = 0x01  # 检测结果 → MCU
CMD_HEARTBEAT = 0x02  # 心跳请求 → MCU
CMD_HEARTBEAT_ACK = 0x03  # 心跳应答 ← MCU
CMD_CONFIG_SET = 0x04  # MCU 设置手机配置 ← MCU
CMD_CONFIG_GET = 0x05  # MCU 请求手机配置 ← MCU
CMD_CONFIG_RESP = 0x06  # 手机配置应答 → MCU
CMD_ERROR = 0xF0  # 错误报告 双向
CMD_UART_WRITE = 0x20  # UART 写入 → HUB
CMD_UART_READ = 0x21   # UART 读取请求 → HUB
CMD_UART_CLOSE = 0x22  # UART 关闭 → HUB
CMD_UART_RESP = 0x23   # UART 读取响应 ← HUB

CMD_NAMES = {
    CMD_DETECTION_RESULT: "DETECTION_RESULT",
    CMD_HEARTBEAT: "HEARTBEAT",
    CMD_HEARTBEAT_ACK: "HEARTBEAT_ACK",
    CMD_CONFIG_SET: "CONFIG_SET",
    CMD_CONFIG_GET: "CONFIG_GET",
    CMD_CONFIG_RESP: "CONFIG_RESP",
    CMD_ERROR: "ERROR",
    CMD_UART_WRITE: "UART_WRITE",
    CMD_UART_READ: "UART_READ",
    CMD_UART_CLOSE: "UART_CLOSE",
    CMD_UART_RESP: "UART_RESP",
}

# ── 错误码 ─────────────────────────────────────────────
ERR_CHECKSUM = 0x01  # 校验和错误
ERR_LENGTH = 0x02  # 长度错误
ERR_UNKNOWN_CMD = 0x03  # 未知命令
ERR_PAYLOAD = 0x04  # 负载格式错误


# ══════════════════════════════════════════════════════
#  核心编解码
# ══════════════════════════════════════════════════════

def checksum(data: bytes) -> int:
    """计算累加和校验（低 8 位）"""
    return sum(data) & 0xFF


def encode(command: int, payload: bytes = b"", target: Optional[int] = None) -> bytes:
    """构造完整帧.

    Args:
        command: 命令字 (0x01~0xF0)
        payload: 负载字节串
        target: HUB 目标端口（None 或 TGT_UNUSED 时不加 Target）
                非 None 时在 payload 前加 1 字节 Target

    Returns:
        完整帧字节串

    Raises:
        ValueError: command 超出 0x00~0xFF 范围
    """
    if not 0x00 <= command <= 0xFF:
        raise ValueError(f"command 超出范围: {command}")

    inner = struct.pack(">H", len(payload) + CMD_LEN)  # Length = Cmd(1) + Payload(N)
    inner += bytes([command])
    inner += payload
    inner += bytes([checksum(inner)])

    return FRAME_HEAD + inner


def decode(data: bytes) -> Optional[tuple[int, bytes]]:
    """从字节流中解析一帧.

    Args:
        data: 待解析的字节流

    Returns:
        (command, payload) 解析成功
        None 数据不足或不合法
    """
    if len(data) < MIN_FRAME_LEN:
        return None

    # 查找帧头
    head_pos = data.find(FRAME_HEAD)
    if head_pos < 0:
        return None

    # 跳过帧头
    remain = data[head_pos + HEAD_LEN:]
    if len(remain) < LENGTH_LEN + CMD_LEN + CHECKSUM_LEN:
        return None

    # 解析长度
    payload_len, = struct.unpack(">H", remain[:LENGTH_LEN])
    total_inner = LENGTH_LEN + payload_len + CHECKSUM_LEN
    if len(remain) < total_inner:
        return None

    inner = remain[:total_inner]
    payload_data = remain[LENGTH_LEN:total_inner - CHECKSUM_LEN]  # Cmd + Payload
    recv_checksum = remain[total_inner - CHECKSUM_LEN]

    # 校验
    expect_cs = checksum(inner[:LENGTH_LEN + payload_len])
    if expect_cs != recv_checksum:
        return None  # 校验失败，丢弃

    command = payload_data[0]
    actual_payload = payload_data[CMD_LEN:]
    return command, actual_payload


# ══════════════════════════════════════════════════════
#  检测结果编码 (高頻路徑)
# ══════════════════════════════════════════════════════

# 每个目标编码: class_id(2B) + confidence(1B) + x1(2B) + y1(2B) + x2(2B) + y2(2B) = 11B
TARGET_ENCODE_SIZE = 11


def encode_target(
    class_id: int,
    confidence: float,
    x1: int, y1: int,
    x2: int, y2: int,
    img_width: int = 65535,
    img_height: int = 65535,
) -> bytes:
    """编码单个检测目标为协议字节.

    Args:
        class_id: 类别 ID (0-65535)
        confidence: 置信度 (0.0-1.0)
        x1, y1, x2, y2: 归一化边界框坐标 (像素值)
        img_width, img_height: 图像尺寸，用于归一化

    Returns:
        11 字节的编码结果
    """
    conf_byte = max(0, min(255, round(confidence * 255)))
    # 归一化到 uint16 范围
    def _norm(val, max_val):
        if max_val <= 0:
            return 0
        return max(0, min(65535, round(val / max_val * 65535)))

    return struct.pack(
        ">H B H H H H",
        class_id & 0xFFFF,
        conf_byte,
        _norm(x1, img_width),
        _norm(y1, img_height),
        _norm(x2, img_width),
        _norm(y2, img_height),
    )


def encode_detection_frame(
    targets: list[tuple],
    img_width: int = 0,
    img_height: int = 0,
) -> bytes:
    """构造检测结果帧 (CMD_DETECTION_RESULT).

    Args:
        targets: 目标列表，每项 (class_id, confidence, x1, y1, x2, y2)
        img_width, img_height: 原始图像尺寸，用于归一化

    Returns:
        完整帧字节串
    """
    payload = bytearray()
    has_size = img_width > 0 and img_height > 0
    for t in targets:
        if has_size:
            payload.extend(encode_target(*t, img_width, img_height))
        else:
            payload.extend(encode_target(*t))

    return encode(CMD_DETECTION_RESULT, bytes(payload))


# ══════════════════════════════════════════════════════
#  解码辅助
# ══════════════════════════════════════════════════════

def decode_target(data: bytes, offset: int = 0) -> Optional[dict]:
    """从字节流的 offset 位置解码一个目标.

    Returns:
        {class_id, confidence, x1, y1, x2, y2} 或 None
    """
    if offset + TARGET_ENCODE_SIZE > len(data):
        return None

    (cid, conf_byte, x1, y1, x2, y2) = struct.unpack_from(
        ">H B H H H H", data, offset
    )
    return {
        "class_id": cid,
        "confidence": round(conf_byte / 255.0, 4),
        "x1": x1, "y1": y1, "x2": x2, "y2": y2,
    }


def decode_detection_payload(payload: bytes) -> list[dict]:
    """解码检测结果负载，返回目标列表."""
    targets = []
    offset = 0
    while offset + TARGET_ENCODE_SIZE <= len(payload):
        t = decode_target(payload, offset)
        if t:
            targets.append(t)
            offset += TARGET_ENCODE_SIZE
        else:
            break
    return targets


# ══════════════════════════════════════════════════════
#  便捷构造
# ══════════════════════════════════════════════════════

def make_heartbeat() -> bytes:
    """构造心跳帧"""
    return encode(CMD_HEARTBEAT)


def make_heartbeat_ack() -> bytes:
    """构造心跳应答帧"""
    return encode(CMD_HEARTBEAT_ACK)


def make_error(error_code: int, message: str = "") -> bytes:
    """构造错误报告帧"""
    msg_bytes = message.encode("utf-8", errors="replace")[:64]
    payload = bytes([error_code]) + struct.pack("B", len(msg_bytes)) + msg_bytes
    return encode(CMD_ERROR, payload)


def cmd_name(cmd: int) -> str:
    """命令字 → 可读名称"""
    return CMD_NAMES.get(cmd, f"UNKNOWN(0x{cmd:02X})")


def format_frame(command: int, payload: bytes) -> str:
    """格式化帧信息用于日志"""
    import binascii
    pay_hex = binascii.hexlify(payload[:16]).decode("ascii")
    if len(payload) > 16:
        pay_hex += "..."
    return f"[{cmd_name(command)}] cmd=0x{command:02X} len={len(payload)} payload={pay_hex}"
