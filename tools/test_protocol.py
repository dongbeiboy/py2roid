"""
Phase 2 验证脚本 — PC 端测试协议编解码正确性

用法:
    python tools/test_protocol.py
"""

import sys
import os

# 把 python/ 目录加到路径，方便直接引用
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..",
                                "android", "app", "src", "main", "python"))

from protocol import (
    encode, decode,
    encode_detection_frame, decode_detection_payload,
    encode_target, decode_target,
    make_heartbeat, make_heartbeat_ack, make_error,
    checksum, cmd_name, format_frame,
    FRAME_HEAD, MIN_FRAME_LEN,
    CMD_DETECTION_RESULT, CMD_HEARTBEAT, CMD_HEARTBEAT_ACK,
    CMD_CONFIG_SET, CMD_CONFIG_GET, CMD_CONFIG_RESP, CMD_ERROR,
    TARGET_ENCODE_SIZE,
)
from utils import (
    format_coords, bytes_to_hex, hex_to_bytes,
    clamp_coords, target_summary,
)


passed = 0
failed = 0


def check(name: str, ok: bool, detail: str = ""):
    global passed, failed
    if ok:
        passed += 1
        print(f"  ✅ {name}")
    else:
        failed += 1
        print(f"  ❌ {name} — {detail}")


# ══════════════════════════════════════════════════════
#  1. 校验和
# ══════════════════════════════════════════════════════
def test_checksum():
    print("\n── 1. 校验和 ──")
    assert checksum(b"") == 0
    assert checksum(b"\x00") == 0
    assert checksum(b"\x01\x02\x03") == 6
    # 超过 255 环绕
    assert checksum(b"\xFF\x01") == 0
    check("空字节", checksum(b"") == 0)
    check("简单累加", checksum(b"\x01\x02\x03") == 6)
    check("环绕", checksum(b"\xFF\x01") == 0)


# ══════════════════════════════════════════════════════
#  2. 编码 / 解码基本帧
# ══════════════════════════════════════════════════════
def test_encode_decode_basic():
    print("\n── 2. 基本编解码 ──")

    # 心跳 (无负载)
    frame = make_heartbeat()
    check("心跳帧长度", len(frame) == MIN_FRAME_LEN,
          f"期望 {MIN_FRAME_LEN}, 实际 {len(frame)}")
    check("心跳帧头正确", frame[:2] == FRAME_HEAD)
    # Length = CMD_LEN(1) + 0 = 1, 大端: 0x00 0x01
    check("心跳 Length=1", frame[2:4] == b"\x00\x01")
    check("心跳 Cmd=0x02", frame[4] == CMD_HEARTBEAT)

    # 解码心跳
    result = decode(frame)
    check("心跳解码成功", result is not None)
    if result:
        cmd, payload = result
        check("心跳命令正确", cmd == CMD_HEARTBEAT)
        check("心跳负载为空", payload == b"")

    # 带负载帧: CONFIG_SET
    import json
    cfg = json.dumps({"threshold": 0.5}).encode("utf-8")
    frame2 = encode(CMD_CONFIG_SET, cfg)
    result2 = decode(frame2)
    check("CONFIG_SET 解码成功", result2 is not None)
    if result2:
        cmd2, payload2 = result2
        check("CONFIG_SET 命令正确", cmd2 == CMD_CONFIG_SET)
        check("CONFIG_SET 负载正确", payload2 == cfg)

    # 合成帧解码
    heartbeat_ack = make_heartbeat_ack()
    result3 = decode(heartbeat_ack)
    check("心跳 ACK 解码", result3 is not None)
    if result3:
        check("心跳 ACK 命令", result3[0] == CMD_HEARTBEAT_ACK)


# ══════════════════════════════════════════════════════
#  3. 校验错误帧拒收
# ══════════════════════════════════════════════════════
def test_checksum_reject():
    print("\n── 3. 校验错误拒收 ──")
    frame = bytearray(make_heartbeat())
    # 篡改最后一个字节(checksum)
    frame[-1] ^= 0xFF
    result = decode(bytes(frame))
    check("校验错误应返回 None", result is None)


# ══════════════════════════════════════════════════════
#  4. 编码 / 解码检测目标
# ══════════════════════════════════════════════════════
def test_detection_target():
    print("\n── 4. 检测目标编解码 ──")

    # 单个目标: class_id=1, conf=0.85, 坐标 (100,200,500,400), 640x480
    encoded = encode_target(1, 0.85, 100, 200, 500, 400, 640, 480)
    check("单目标编码长度", len(encoded) == TARGET_ENCODE_SIZE,
          f"期望 {TARGET_ENCODE_SIZE}, 实际 {len(encoded)}")

    decoded = decode_target(encoded)
    check("单目标解码成功", decoded is not None)
    if decoded:
        check("class_id=1", decoded["class_id"] == 1)
        check("conf≈0.85", abs(decoded["confidence"] - 0.85) < 0.01)
        # 归一化后回算: 100/640*65535 ≈ 10240
        check("x1≈10240", abs(decoded["x1"] - 10240) < 2)

    # 多个目标合成帧
    targets = [
        (0, 0.92, 50, 60, 200, 300),
        (1, 0.78, 400, 100, 600, 450),
        (2, 0.45, 0, 0, 320, 240),
    ]
    frame = encode_detection_frame(targets, 640, 480)
    result = decode(frame)
    check("检测帧解码成功", result is not None)
    if result:
        cmd, payload = result
        check("检测帧命令=0x01", cmd == CMD_DETECTION_RESULT)
        check("负载长度=3×11=33", len(payload) == len(targets) * TARGET_ENCODE_SIZE)

        decoded_targets = decode_detection_payload(payload)
        check(f"解码出 {len(decoded_targets)} 目标",
              len(decoded_targets) == len(targets))
        if len(decoded_targets) >= 2:
            check("目标1 class_id=0", decoded_targets[0]["class_id"] == 0)
            check("目标1 conf≈0.92",
                  abs(decoded_targets[0]["confidence"] - 0.92) < 0.01)
            check("目标2 class_id=1", decoded_targets[1]["class_id"] == 1)
            check("目标2 conf≈0.78",
                  abs(decoded_targets[1]["confidence"] - 0.78) < 0.01)


# ══════════════════════════════════════════════════════
#  5. 错误帧
# ══════════════════════════════════════════════════════
def test_error_frame():
    print("\n── 5. 错误帧 ──")
    err_frame = make_error(0x01, "checksum error")
    result = decode(err_frame)
    check("错误帧解码成功", result is not None)
    if result:
        check("错误帧命令=0xF0", result[0] == CMD_ERROR)
        payload = result[1]
        check("错误码=0x01", len(payload) >= 1 and payload[0] == 0x01)
        if len(payload) >= 2:
            msg_len = payload[1]
            msg = payload[2:2+msg_len].decode("utf-8")
            check("错误信息正确", msg == "checksum error")


# ══════════════════════════════════════════════════════
#  6. 边界条件
# ══════════════════════════════════════════════════════
def test_edge_cases():
    print("\n── 6. 边界条件 ──")

    # 空数据
    check("空数据解码", decode(b"") is None)
    # 不足最小长度
    check("短数据解码", decode(b"\xAA\x55") is None)
    # 无帧头
    check("无帧头解码", decode(b"\x00\x01\x02\x03\x04\x05\x06") is None)
    # 帧头不在开头
    data = b"\x00" * 10 + make_heartbeat()
    result = decode(data)
    check("偏移帧头解码", result is not None and result[0] == CMD_HEARTBEAT)

    # conf 边界
    encoded = encode_target(0, 0.0, 0, 0, 0, 0)
    decoded = decode_target(encoded)
    check("conf=0.0", decoded and decoded["confidence"] == 0.0)

    encoded = encode_target(0, 1.0, 0, 0, 0, 0)
    decoded = decode_target(encoded)
    check("conf=1.0", decoded and abs(decoded["confidence"] - 1.0) < 0.01)

    # class_id 边界
    encoded = encode_target(65535, 0.5, 0, 0, 0, 0)
    decoded = decode_target(encoded)
    check("class_id=65535", decoded and decoded["class_id"] == 65535)


# ══════════════════════════════════════════════════════
#  7. utils 功能
# ══════════════════════════════════════════════════════
def test_utils():
    print("\n── 7. utils ──")

    # format_coords
    s = format_coords(100, 200, 500, 400)
    check("坐标格式化整数", s == "(100,200,500,400)")

    s = format_coords(0, 0, 65535, 65535, norm=True, precision=2)
    check("坐标格式化归一化", s == "(0.00,0.00,1.00,1.00)")

    # bytes_to_hex
    h = bytes_to_hex(b"\xAA\x55\x01")
    check("字节转十六进制", h == "AA 55 01")

    h = bytes_to_hex(b"")
    check("空字节十六进制", h == "(empty)")

    # hex_to_bytes
    b = hex_to_bytes("AA 55 01")
    check("十六进制转字节", b == b"\xAA\x55\x01")

    b = hex_to_bytes("invalid")
    check("非法十六进制", b is None)

    # clamp_coords
    result = clamp_coords(-10, 50, 700, 500, 640, 480)
    check("坐标裁剪下限", result[0] == 0)
    check("坐标裁剪上限", result[2] == 640)
    check("坐标排序", clamp_coords(500, 0, 100, 100, 640, 480) == (100, 0, 500, 100))

    # target_summary
    targets = [{"class_id": 1, "confidence": 0.85, "x1": 100, "y1": 200, "x2": 500, "y2": 400}]
    summary = target_summary(targets)
    check("目标摘要", "cls=1" in summary and "conf=0.85" in summary)


# ══════════════════════════════════════════════════════
#  8. cmd_name / format_frame
# ══════════════════════════════════════════════════════
def test_cmd_info():
    print("\n── 8. 命令信息 ──")
    check("已知命令名", cmd_name(CMD_HEARTBEAT) == "HEARTBEAT")
    check("未知命令名", cmd_name(0xFF) == "UNKNOWN(0xFF)")

    frame = make_heartbeat()
    result = decode(frame)
    if result:
        info = format_frame(*result)
        check("format_frame", "HEARTBEAT" in info and "cmd=0x02" in info)


# ══════════════════════════════════════════════════════
#  运行
# ══════════════════════════════════════════════════════
if __name__ == "__main__":
    print(f"py2roid 协议层验证 — Python {sys.version.split()[0]}")
    print("=" * 50)

    test_checksum()
    test_encode_decode_basic()
    test_checksum_reject()
    test_detection_target()
    test_error_frame()
    test_edge_cases()
    test_utils()
    test_cmd_info()

    total = passed + failed
    print(f"\n{'=' * 50}")
    print(f"总计: {total}  通过: {passed}  失败: {failed}")
    if failed:
        sys.exit(1)
    else:
        print("全部通过 ✅")
