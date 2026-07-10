#!/usr/bin/env python3
"""直接调 onnx2tf.convert() 转 ONNX→TFLite"""
import sys
import numpy as np
np_load_old = np.load
np.load = lambda *a, **kw: np_load_old(*a, **{**kw, 'allow_pickle': True})

import onnx2tf
import onnx2tf.onnx2tf as ot

# 跳过下载测试图片——patch 到 onnx2tf 模块内部
ot.download_test_image_data = lambda: np.random.randn(1, 3, 640, 640).astype(np.float32)

onnx_path = sys.argv[1]
output_dir = sys.argv[2]

onnx2tf.convert(
    input_onnx_file_path=onnx_path,
    output_folder_path=output_dir,
    non_verbose=True,
)

print(f"Done -> {output_dir}")
