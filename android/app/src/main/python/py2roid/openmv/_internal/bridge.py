"""
Kotlin ↔ Python 桥接适配器。

通过 Chaquopy jclass 直接调用 Kotlin PythonBridge 单例。
"""

from __future__ import annotations

import struct
from typing import Optional

from java import jclass, JArray
from protocol import (
    encode, decode,
    CMD_UART_WRITE, CMD_UART_READ, CMD_UART_CLOSE, CMD_UART_RESP,
)

_KtBridge = jclass("com.xz.py2roid.bridge.PythonBridge")

# Target 编码（与 HUB 协议一致）
TGT_UNUSED = 0xFF
TGT_UART1 = 0x01
TGT_UART2 = 0x02
TGT_UART3 = 0x03
TGT_UART4 = 0x04
TGT_UART5 = 0x05
TGT_UART6 = 0x06

# 会话级缓存
_hub_target_cache: dict[int, Optional[int]] = {}
_prev_seq: int = -1
_last_frame: Optional[tuple[bytes, int]] = None


def _reset():
    """重置会话级缓存（由 ScriptRunner 启动时调用）。"""
    global _hub_target_cache, _prev_seq, _last_frame
    _hub_target_cache = {}
    _prev_seq = -1
    _last_frame = None


def get_frame_bytes(width: int, height: int, pixformat: int) -> Optional[bytes]:
    """通过 Chaquopy JNI 获取最新相机帧，并转换到目标格式。

    Args:
        width: 目标宽度
        height: 目标高度
        pixformat: 目标像素格式 (1=RGB565, 2=GRAYSCALE)

    Returns:
        转换后的像素数据 bytes，或 None（帧尚未就绪）
    """
    global _prev_seq, _last_frame

    # 调用 Kotlin 侧 getFrame(prevSeq) → Pair<ByteArray?, Long>
    result = _KtBridge.getFrame(_prev_seq)
    if result is None:
        return _last_frame[0] if _last_frame else None

    data_java, seq = result
    seq = int(seq)
    if data_java is None:
        return _last_frame[0] if _last_frame else None

    if seq == _prev_seq:
        return _last_frame[0] if _last_frame else None

    _prev_seq = seq

    # data_java is JArray('byte') — 包含格式: [NV21_data + fmt_byte + w(2byte) + h(2byte) + rotation_byte]
    raw = bytes(data_java)

    if len(raw) < 5:
        return None

    # 从 payload 尾部读取元信息
    src_fmt = raw[-1]
    src_w = (raw[-3] << 8) | raw[-4]
    src_h = (raw[-5] << 8) | raw[-6]
    payload = raw[:-6]

    import numpy as np

    if src_fmt == 3:  # NV21
        converted = _nv21_to_target(payload, src_w, src_h, width, height, pixformat)
    else:
        # 未知格式，直接返回
        converted = payload

    _last_frame = (converted, seq)
    return converted


def _nv21_to_target(
    nv21: bytes, src_w: int, src_h: int,
    dst_w: int, dst_h: int, dst_fmt: int,
) -> bytes:
    """NV21 → RGB565 或 GRAYSCALE，带缩放。"""
    import numpy as np

    y_size = src_w * src_h
    uv_size = src_w * src_h // 2

    y = np.frombuffer(nv21[:y_size], dtype=np.uint8).reshape(src_h, src_w)

    if dst_fmt == 2:  # GRAYSCALE
        if dst_w != src_w or dst_h != src_h:
            return _nearest_neighbor(y, dst_w, dst_h).tobytes()
        return y.tobytes()

    # RGB565
    uv = np.frombuffer(nv21[y_size:y_size + uv_size], dtype=np.uint8).reshape(src_h // 2, src_w)
    # NV21: UV 平面是 V0,U0,V1,U1,... 交错排列，偶数列=V 奇数列=U
    v = uv[:, 0::2]
    u = uv[:, 1::2]
    u = np.repeat(np.repeat(u, 2, axis=0), 2, axis=1)[:src_h, :src_w]
    v = np.repeat(np.repeat(v, 2, axis=0), 2, axis=1)[:src_h, :src_w]

    y_f = y.astype(np.float32)
    u_f = u.astype(np.float32) - 128.0
    v_f = v.astype(np.float32) - 128.0

    r = np.clip(y_f + 1.402 * v_f, 0, 255).astype(np.uint8)
    g = np.clip(y_f - 0.344 * u_f - 0.714 * v_f, 0, 255).astype(np.uint8)
    b = np.clip(y_f + 1.772 * u_f, 0, 255).astype(np.uint8)

    if dst_w != src_w or dst_h != src_h:
        r = _nearest_neighbor(r, dst_w, dst_h)
        g = _nearest_neighbor(g, dst_w, dst_h)
        b = _nearest_neighbor(b, dst_w, dst_h)

    rgb565 = (np.uint16(r >> 3) << 11) | (np.uint16(g >> 2) << 5) | np.uint16(b >> 3)
    return rgb565.tobytes()


def _nearest_neighbor(arr: np.ndarray, dst_w: int, dst_h: int) -> np.ndarray:
    """最近邻缩放 2D 数组。"""
    src_h, src_w = arr.shape
    rows = np.linspace(0, src_h - 1, dst_h).astype(int)
    cols = np.linspace(0, src_w - 1, dst_w).astype(int)
    return arr[rows][:, cols]


def resolve_uart_target(uart_id: int) -> Optional[int]:
    """探测当前连接的是 HUB 还是单 MCU，返回目标端口。

    结果在会话级别缓存，首次探测后不再重复 PING。
    """
    if uart_id in _hub_target_cache:
        return _hub_target_cache[uart_id]

    # 简易探测：检查 Kotlin 端 UsbSerialManager 是否已连接
    from java import jclass
    usbMgr = jclass("com.xz.py2roid.serial.UsbSerialManager")
    is_connected = usbMgr.isConnected()

    if is_connected:
        # 连接到 HUB：映射到 TGT_UARTx
        target = TGT_UART1 + (uart_id - 1)
    else:
        # 直连单 MCU：LEGACY 模式
        target = None

    _hub_target_cache[uart_id] = target
    return target


def uart_write(target: Optional[int], data: bytes) -> int:
    """通过协议编码后发送到 UART。

    Args:
        target: HUB 目标端口 (TGT_UARTx)，None 表示直连
        data: 要发送的数据

    Returns:
        写入的字节数
    """
    frame = encode(CMD_UART_WRITE, data,
                   target=target if target is not None else TGT_UNUSED)
    _KtBridge.sendToUsb(frame)
    return len(data)


def uart_read(target: Optional[int], n: int) -> bytes:
    """从 UART 读取 n 字节。

    向 HUB 发送 CMD_UART_READ 请求，轮询等待响应帧。
    """
    import struct

    req = encode(CMD_UART_READ, struct.pack(">H", n),
                 target=target if target is not None else TGT_UNUSED)
    _KtBridge.sendToUsb(req)

    # 轮询等待响应
    deadline = 100  # 最多 100 次轮询 ≈ 1s
    while deadline > 0:
        resp = _KtBridge.readUsbResponse(n + 64)
        if resp is not None:
            resp_bytes = bytes(resp)
            _, payload = decode(resp_bytes)
            return payload if payload else b""
        import time
        time.sleep(0.01)  # 10ms 间隔
        deadline -= 1

    return b""


def uart_available(target: Optional[int]) -> int:
    """查询指定 UART 端口的可读字节数。"""
    import struct

    req = encode(CMD_UART_READ, b"\x00\x00",
                 target=target if target is not None else TGT_UNUSED)
    _KtBridge.sendToUsb(req)

    deadline = 50
    while deadline > 0:
        resp = _KtBridge.readUsbResponse(8)
        if resp is not None:
            resp_bytes = bytes(resp)
            _, payload = decode(resp_bytes)
            return struct.unpack(">H", payload)[0] if payload else 0
        import time
        time.sleep(0.01)
        deadline -= 1

    return 0


def uart_close(target: Optional[int]):
    """关闭 UART 连接。"""
    frame = encode(CMD_UART_CLOSE, b"",
                   target=target if target is not None else TGT_UNUSED)
    _KtBridge.sendToUsb(frame)


def ml_predict(input_data: bytes, model_name: str, backend: str) -> Optional[str]:
    """通过 PythonBridge 调用 Kotlin Detector 推理。

    Returns:
        JSON 字符串，或 None
    """
    result_json = _KtBridge.mlPredict(input_data, model_name, backend)
    return result_json if result_json else None
