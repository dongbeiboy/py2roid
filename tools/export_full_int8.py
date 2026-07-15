#!/usr/bin/env python3
"""
True Full INT8 TFLite Export with representative calibration.

Pipeline: .pt → ONNX → TF SavedModel → TFLite (full INT8, UINT8 output)
Uses COCO val2017 images for calibration.
"""
import os, sys, glob, cv2, numpy as np
os.environ["ULTRALYTICS_DISABLE_VERSION_CHECK"] = "1"

# Patch ultralytics check_requirements to skip tensorflow
import ultralytics.utils.checks as _checks
_orig = _checks.check_requirements
def _patched_check(requirements=(), *args, **kwargs):
    if isinstance(requirements, list):
        requirements = [r for r in requirements if "tensorflow" not in str(r)]
    if isinstance(requirements, str) and "tensorflow" in requirements:
        return True
    return _orig(requirements, *args, **kwargs)
_checks.check_requirements = _patched_check

MODEL_NAME = sys.argv[1] if len(sys.argv) > 1 else "yolov8n"
CALIB_DIR = sys.argv[2] if len(sys.argv) > 2 else "tools/eval/coco_images/val2017"

# ── Step 1: Export to ONNX ──
from ultralytics import YOLO
pt_path = f"noob/{MODEL_NAME}.pt"
print(f"[1/4] Exporting {pt_path} → ONNX ...")
m = YOLO(pt_path)
onnx_path = m.export(format="onnx", imgsz=640)
print(f"      ONNX: {onnx_path}")

# ── Step 2: ONNX → TF SavedModel ──
import onnx2tf
print(f"[2/4] ONNX → TF SavedModel ...")
saved_model_dir = f"noob/{MODEL_NAME}_saved_model"
# Use onnx2tf to convert (this produces float saved_model)
onnx2tf.convert(
    input_onnx_file_path=onnx_path,
    output_folder_path=saved_model_dir,
    verbosity="error",
)

# ── Step 3: Calibration dataset generator ──
print(f"[3/4] Building calibration dataset from {CALIB_DIR} ...")
calib_images = sorted(glob.glob(os.path.join(CALIB_DIR, "*.jpg")))
if len(calib_images) < 10:
    print(f"  WARNING: only {len(calib_images)} images found, need more!")
np.random.shuffle(calib_images)
# Use up to 300 images for calibration
calib_images = calib_images[:300]
print(f"  Using {len(calib_images)} images for calibration")

def representative_dataset():
    """Yield preprocessed images matching model input (1,640,640,3) float32 [0,1]."""
    for img_path in calib_images:
        img = cv2.imread(img_path)
        if img is None:
            continue
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        img = cv2.resize(img, (640, 640))
        img = img.astype(np.float32) / 255.0
        img = np.expand_dims(img, axis=0)  # (1,640,640,3)
        yield [img]

# ── Step 4: TFLite Converter with full INT8 quantization ──
import tensorflow as tf
print(f"[4/4] TFLite Converter: full INT8 with calibration ...")
converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)

# Full integer quantization
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.representative_dataset = representative_dataset

# Force INT8 ops (this is what makes output UINT8)
converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]

# Keep float I/O for compatibility (or switch to UINT8)
converter.inference_input_type = tf.float32
converter.inference_output_type = tf.uint8   # ← this gives us UINT8 output!

tflite_model = converter.convert()

# ── Save ──
tflite_path = f"noob/{MODEL_NAME}_full_int8.tflite"
with open(tflite_path, "wb") as f:
    f.write(tflite_model)
size_mb = os.path.getsize(tflite_path) / 1e6
print(f"\n✅ SUCCESS: {tflite_path} ({size_mb:.1f} MB)")

# Verify
interp = tf.lite.Interpreter(model_path=tflite_path)
interp.allocate_tensors()
in_details = interp.get_input_details()[0]
out_details = interp.get_output_details()[0]
print(f"   Input:  {in_details['shape']} dtype={in_details['dtype'].__name__}")
print(f"   Output: {out_details['shape']} dtype={out_details['dtype'].__name__} "
      f"scale={out_details['quantization'][0]} zp={out_details['quantization'][1]}")
