#!/usr/bin/env python3
"""Export last.pt to full int8 TFLite"""
import os
os.environ["ULTRALYTICS_DISABLE_VERSION_CHECK"] = "1"

# 手动 patch ultralytics 版本检查，跳过 tensorflow 版本约束
import ultralytics.utils.checks as _checks
_orig = _checks.check_requirements

def _patched_check(requirements=(), *args, **kwargs):
    if isinstance(requirements, list):
        requirements = [r for r in requirements if "tensorflow" not in str(r)]
    if isinstance(requirements, str) and "tensorflow" in requirements:
        return True
    return _orig(requirements, *args, **kwargs)

_checks.check_requirements = _patched_check

from ultralytics import YOLO

m = YOLO("noob/last.pt")
m.export(format="tflite", imgsz=640, int8=True)
import shutil
# ultralytics puts the tflite inside the saved_model folder
src = "noob/last_saved_model/last_int8.tflite"
dst = "noob/last_full_int8.tflite"
if os.path.exists(src):
    shutil.copy2(src, dst)
    print(f"SUCCESS: {dst} ({os.path.getsize(dst) / 1e6:.1f} MB)")
else:
    print("WARNING: TFLite export may have failed, file not found")
