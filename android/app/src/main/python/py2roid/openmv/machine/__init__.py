"""py2roid.openmv.machine — 硬件接口兼容层"""

from .UART import UART
from .Pin import Pin

__all__ = ["UART", "Pin"]
