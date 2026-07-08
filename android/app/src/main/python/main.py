"""
py2roid Python 层入口

通过 Chaquopy 暴露给 Kotlin 调用：

    # Kotlin 侧
    val py = Python.getInstance()
    val module = py.getModule("main")
    module.callAttr("init")
    frame = module.callAttr("encode_detection", targetList)

    # Python 调 Kotlin 回调
    pythonBridge.callAttr("on_data_received", data)
"""

from __future__ import annotations

from typing import Any, Callable, Optional

from protocol import (
    encode_detection_frame,
    decode,
    make_heartbeat_ack,
    cmd_name,
    format_frame,
    CMD_HEARTBEAT,
    CMD_CONFIG_GET,
    CMD_CONFIG_SET,
    CMD_ERROR,
)
from utils import log_i, log_w, log_e, bytes_to_hex, target_summary

TAG = "py2roid.py"

# ── Kotlin 回调注册 ─────────────────────────────────
# Python 侧只能持有回调引用，实际由 Kotlin 桥接注入

_send_to_usb: Optional[Callable[[bytes], None]] = None
_send_to_ws: Optional[Callable[[bytes], None]] = None
_on_config_request: Optional[Callable[[], dict]] = None
_on_error: Optional[Callable[[int, str], None]] = None


def register_callback(name: str, callback: Callable) -> None:
    """注册 Kotlin 回调.

    Args:
        name: 回调名称，支持:
            - "send_to_usb":  发送 bytes 到 USB 串口
            - "send_to_ws":   发送 bytes 到 WebSocket
            - "config_request":  返回当前配置 dict
            - "on_error":      错误通知 (error_code, message)
        callback: 对应的可调用对象
    """
    global _send_to_usb, _send_to_ws, _on_config_request, _on_error
    mapping = {
        "send_to_usb": "_send_to_usb",
        "send_to_ws": "_send_to_ws",
        "config_request": "_on_config_request",
        "on_error": "_on_error",
    }
    var = mapping.get(name)
    if var is None:
        log_w(TAG, f"未知回调名称: {name}")
        return
    globals()[var] = callback
    log_i(TAG, f"注册回调: {name}")


# ── 暴露给 Kotlin 的函数 ────────────────────────────

def init(config: Optional[dict] = None) -> None:
    """Python 层初始化（由 Kotlin 在启动时调用）.

    Args:
        config: 可选初始化配置，支持:
            - log_level: "DEBUG" / "INFO" / "WARN" / "ERROR"
    """
    from utils import set_log_level
    if config:
        level = config.get("log_level", "INFO")
        set_log_level(level)
        log_i(TAG, f"初始化完成, log_level={level}")
    else:
        log_i(TAG, "初始化完成 (default)")


def encode_detection(
    targets: list[list],
    img_width: int = 0,
    img_height: int = 0,
) -> bytearray:
    """将检测目标列表编码为协议帧（Kotlin 调用）.

    Args:
        targets: 目标列表，每项 [class_id, confidence, x1, y1, x2, y2]
        img_width: 图像宽（0 则不做归一化）
        img_height: 图像高

    Returns:
        bytearray 帧数据，可直接传给 UsbSerialManager.send()
    """
    # 转为 tuple 列表
    items = [(t[0], t[1], t[2], t[3], t[4], t[5]) for t in targets]
    frame = encode_detection_frame(items, img_width, img_height)
    log_i(TAG, f"编码检测帧: {len(targets)} 目标, {len(frame)}B")
    return bytearray(frame)


def on_data_received(data: bytes) -> None:
    """处理从 MCU 收到的数据（由 Kotlin 在串口/WebSocket 收到数据时调用）.

    Args:
        data: 原始字节数据
    """
    result = decode(data)
    if result is None:
        log_w(TAG, f"收到无法解析的数据: {bytes_to_hex(data)}")
        return

    command, payload = result
    log_i(TAG, f"收到帧: {format_frame(command, payload)}")

    if command == CMD_HEARTBEAT:
        _handle_heartbeat()
    elif command == CMD_CONFIG_GET:
        _handle_config_get()
    elif command == CMD_CONFIG_SET:
        _handle_config_set(payload)
    elif command == CMD_ERROR:
        _handle_error(payload)
    else:
        log_w(TAG, f"未处理命令: {cmd_name(command)}")


# ── 内部处理 ────────────────────────────────────────

def _handle_heartbeat() -> None:
    """响应心跳：回复 ACK"""
    log_i(TAG, "心跳 → ACK")
    frame = make_heartbeat_ack()
    if _send_to_usb:
        _send_to_usb(frame)
    else:
        log_w(TAG, "USB 回调未注册，无法发送心跳应答")


def _handle_config_get() -> None:
    """响应配置请求"""
    if _on_config_request:
        config = _on_config_request()
        from protocol import encode, CMD_CONFIG_RESP
        import json
        payload = json.dumps(config).encode("utf-8")
        frame = encode(CMD_CONFIG_RESP, payload)
        log_i(TAG, f"配置请求 → 响应 ({len(payload)}B)")
        if _send_to_usb:
            _send_to_usb(frame)
    else:
        log_w(TAG, "config_request 回调未注册")


def _handle_config_set(payload: bytes) -> None:
    """处理 MCU 下发的配置"""
    try:
        import json
        config = json.loads(payload.decode("utf-8"))
        log_i(TAG, f"收到配置更新: {config}")
        # TODO: 通过回调通知 Kotlin 更新 SharedPreferences
        # 当前仅记录日志，后续 Phase 6 注册回调后实现
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        log_e(TAG, f"配置解析失败: {e}")


def _handle_error(payload: bytes) -> None:
    """处理错误报告"""
    if len(payload) >= 1:
        err_code = payload[0]
        err_msg = ""
        if len(payload) >= 2:
            msg_len = payload[1]
            err_msg = payload[2:2+msg_len].decode("utf-8", errors="replace")
        log_e(TAG, f"MCU 错误: code=0x{err_code:02X} msg={err_msg}")
        if _on_error:
            _on_error(err_code, err_msg)
