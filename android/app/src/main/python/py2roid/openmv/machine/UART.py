"""
OpenMV machine.UART 兼容实现。

所有读写操作通过协议层编码为带 Target 字段的帧，
经 UsbSerialManager 发给 HUB 路由到对应物理 UART。

无 HUB 直连模式下，Target 设为 TGT_UNUSED。
"""

from __future__ import annotations

from typing import Optional


class UART:
    """OpenMV machine.UART 兼容实现。

    id 参数映射到 HUB UART 端口号 (1~6)。

    Usage:
        uart = UART(3, 115200)
        uart.write("hello")
        data = uart.read(10)
        uart.deinit()
    """

    INIT = 0
    ANY = -1

    def __init__(
        self, id: int, baudrate: int = 115200,
        timeout: int = 1000, timeout_char: int = 0,
    ):
        self._id = id
        self._baudrate = baudrate
        self._timeout = timeout

        from .._internal import bridge as _bridge
        self._target = _bridge.resolve_uart_target(id)

    def any(self) -> int:
        """返回接收缓冲区中可读字节数。"""
        from .._internal import bridge as _bridge
        return _bridge.uart_available(self._target)

    def read(self, n_bytes: int = -1) -> bytes:
        """读取 n_bytes 字节。

        Args:
            n_bytes: ANY(-1) 读所有可用，否则读 n_bytes

        Returns:
            读取的字节数据
        """
        from .._internal import bridge as _bridge
        if n_bytes == self.ANY or n_bytes < 0:
            n_bytes = max(1, self.any())
        return _bridge.uart_read(self._target, n_bytes)

    def readline(self) -> bytes:
        """读一行（以 \\n 结尾）。"""
        from .._internal import bridge as _bridge
        buf = b""
        while True:
            c = _bridge.uart_read(self._target, 1)
            if not c:
                break
            buf += c
            if c == b"\n":
                break
        return buf

    def read_into(self, buf: bytearray, n_bytes: int = -1) -> int:
        """读取到指定 buffer。

        Returns:
            实际读取字节数
        """
        from .._internal import bridge as _bridge
        n = n_bytes if n_bytes > 0 else len(buf)
        data = _bridge.uart_read(self._target, n)
        buf[:len(data)] = data
        return len(data)

    def write(self, buf: bytes | str | bytearray) -> int:
        """写入数据。

        Args:
            buf: 要写入的数据（str 自动编码为 utf-8）

        Returns:
            写入字节数
        """
        from .._internal import bridge as _bridge
        if isinstance(buf, str):
            buf = buf.encode("utf-8")
        return _bridge.uart_write(self._target, bytes(buf))

    def deinit(self):
        """关闭 UART 连接。"""
        from .._internal import bridge as _bridge
        _bridge.uart_close(self._target)

    def __repr__(self):
        return f"<UART id={self._id} baudrate={self._baudrate}>"
