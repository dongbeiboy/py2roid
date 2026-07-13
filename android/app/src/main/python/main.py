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


# ── 桥接帧转换（供 PythonBridge.getFrameBytes 调用） ──

def _bridge_convert_frame(
    data: bytes,
    src_w: int, src_h: int, src_fmt: int,
    dst_w: int, dst_h: int, dst_fmt: int,
) -> list:
    """将 Kotlin 侧的帧缓存转换为 OpenMV sensor.snapshot() 所需格式。

    Args:
        data: 原始帧数据（一般为 NV21）
        src_w, src_h, src_fmt: 源帧宽、高、格式（1=RGB565, 2=GRAYSCALE, 3=NV21）
        dst_w, dst_h, dst_fmt: 目标宽、高、格式（1=RGB565, 2=GRAYSCALE）

    Returns:
        [converted_bytes, actual_format]: 转换后的像素数据和实际格式
    """
    import numpy as np

    try:
        if src_fmt == 3:  # NV21
            # NV21 → RGB565（降采样到目标分辨率）
            # 先解析 NV21 的 Y 和 UV 平面
            y_size = src_w * src_h
            uv_size = src_w * src_h // 2
            y_plane = np.frombuffer(data[:y_size], dtype=np.uint8).reshape(src_h, src_w)
            uv_plane = np.frombuffer(data[y_size:y_size + uv_size], dtype=np.uint8).reshape(src_h // 2, src_w)

            if dst_fmt == 2:  # GRAYSCALE
                if dst_w != src_w or dst_h != src_h:
                    # 最近邻缩放到目标分辨率
                    out_h, out_w = dst_h, dst_w
                    y_small = y_plane[
                        np.linspace(0, src_h - 1, out_h).astype(int)
                    ][:, np.linspace(0, src_w - 1, out_w).astype(int)]
                    return [bytearray(y_small.tobytes()), dst_fmt]
                return [bytearray(y_plane.tobytes()), dst_fmt]
            else:  # RGB565
                # NV21 → BGR（近似）
                import struct
                # 简化：只取 Y 平面 + 最近邻上采样 UV
                uv = np.repeat(np.repeat(uv_plane, 2, axis=0), 2, axis=1)
                u = uv[:, :src_w]
                v = uv[:, src_w:]

                # YUV → RGB
                y = y_plane.astype(np.float32)
                u = u.astype(np.float32) - 128.0
                v = v.astype(np.float32) - 128.0

                r = np.clip(y + 1.402 * v, 0, 255).astype(np.uint8)
                g = np.clip(y - 0.344 * u - 0.714 * v, 0, 255).astype(np.uint8)
                b = np.clip(y + 1.772 * u, 0, 255).astype(np.uint8)

                # RGB → RGB565 (R:5, G:6, B:5)
                if dst_w != src_w or dst_h != src_h:
                    out_h, out_w = dst_h, dst_w
                    rows = np.linspace(0, src_h - 1, out_h).astype(int)
                    cols = np.linspace(0, src_w - 1, out_w).astype(int)
                    r = r[rows][:, cols]
                    g = g[rows][:, cols]
                    b = b[rows][:, cols]

                rgb565 = (np.uint16(r >> 3) << 11) | \
                         (np.uint16(g >> 2) << 5) | \
                         np.uint16(b >> 3)
                return [bytearray(rgb565.tobytes()), dst_fmt]

        # 未知源格式，直接返回原数据
        log_w(TAG, f"_bridge_convert_frame: 未知源格式 src_fmt={src_fmt}")
        return [bytearray(data), src_fmt]

    except Exception as e:
        log_e(TAG, f"_bridge_convert_frame 失败: {e}")
        return [bytearray(data), src_fmt]


# ── OpenMV 模式集成 ──────────────────────────────────

import sys as _sys
import io as _io

_original_stdout = _sys.stdout
_script_stdout_buffer = _io.StringIO()


class _ScriptStdout:
    """捕获脚本 print() 输出，用于 UI 控制台显示。"""

    def __init__(self):
        self._console = _io.StringIO()

    def write(self, text: str) -> None:
        self._console.write(text)
        _original_stdout.write(text)
        # 通过 Kotlin 回调实时推送（可选）
        if text.strip():
            log_i("Script", text.rstrip())

    def flush(self) -> None:
        self._console.flush()
        _original_stdout.flush()

    def getvalue(self) -> str:
        return self._console.getvalue()


_script_stdout = _ScriptStdout()


def _redirect_stdout():
    """重定向 stdout 到 _ScriptStdout。"""
    global _original_stdout
    _original_stdout = _sys.stdout
    _sys.stdout = _script_stdout


def _restore_stdout():
    """恢复原始 stdout。"""
    _sys.stdout = _original_stdout


def _reset_stop_flag():
    """复位脚本停止标志。"""
    from py2roid.openmv._internal import interruptible
    interruptible._stop_flag = False


def _set_stop_flag():
    """设置脚本停止标志。"""
    from py2roid.openmv._internal import interruptible
    interruptible._stop_flag = True


def init_openmv():
    """初始化 OpenMV Python 兼容层（由 ScriptRunner 调用）。"""
    try:
        from py2roid.openmv import sensor, Image, ml, time, machine
        from py2roid.openmv.time import _init as _time_init
        _time_init()
        log_i(TAG, "OpenMV 兼容层初始化完成")
    except Exception as e:
        log_e(TAG, f"OpenMV 兼容层初始化失败: {e}")
        raise


def _exec_script(content: str, filename: str = "<script>"):
    """执行 OpenMV 用户脚本。

    Args:
        content: 脚本源代码
        filename: 脚本文件名（用于错误栈）
    """
    import py2roid.openmv as _openmv
    from py2roid.openmv._internal import interruptible

    namespace = {
        "__name__": "__main__",
        "__file__": filename,
        "sensor": _openmv.sensor,
        "Image": _openmv.Image,
        "ml": _openmv.ml,
        "time": _openmv.time,
        "machine": _openmv.machine,
        "interruptible": interruptible,
    }

    try:
        compiled = compile(content, filename, "exec")
        exec(compiled, namespace)
    except SystemExit:
        log_i(TAG, "脚本被用户停止")
    except Exception as e:
        log_e(TAG, f"脚本执行错误: {e}")
        raise
