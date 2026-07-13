"""脚本中断检测。

ScriptRunner 停止脚本时设置 _stop_flag=True，
所有阻塞操作（sleep_ms, snapshot 等）定期检查此标志。
"""

_stop_flag = False


def _check_stop():
    """检查脚本是否被要求停止，是则抛出 SystemExit。"""
    global _stop_flag
    if _stop_flag:
        _stop_flag = False
        raise SystemExit("Script stopped by user")


def _set_stop():
    """设置停止标志（由 ScriptRunner 调用）。"""
    global _stop_flag
    _stop_flag = True
