"""查看 TFLite 模型输入输出信息和量化参数"""
import sys
import numpy as np

try:
    import tflite_runtime.interpreter as tflite
except ImportError:
    from tensorflow import lite as tflite

models = [
    ("yolov8n_dynamic_range_quant", r"f:\develop\github\py2roid-main\noob\yolov8n_dynamic_tflite\yolov8n_dynamic_range_quant.tflite"),
    ("yolov8n_float16",           r"f:\develop\github\py2roid-main\noob\yolov8n_dynamic_tflite\yolov8n_float16.tflite"),
    ("yolov8n_float32",           r"f:\develop\github\py2roid-main\noob\yolov8n_dynamic_tflite\yolov8n_float32.tflite"),
    ("last_dynamic_range_quant",  r"f:\develop\github\py2roid-main\noob\last_int8_tflite\last_dynamic_range_quant.tflite"),
    ("last_float16",              r"f:\develop\github\py2roid-main\noob\last_int8_tflite\last_float16.tflite"),
    ("last_float32",              r"f:\develop\github\py2roid-main\noob\last_int8_tflite\last_float32.tflite"),
]

for name, path in models:
    try:
        interp = tflite.Interpreter(model_path=path)
        interp.allocate_tensors()
        in_det = interp.get_input_details()[0]
        out_det = interp.get_output_details()[0]

        print(f"\n{'='*60}")
        print(f"[{name}]")
        print(f"  Size: {__import__('os').path.getsize(path)/1024/1024:.1f} MB")
        print(f"  Input:")
        print(f"    shape:  {in_det['shape']}")
        print(f"    dtype:  {in_det['dtype']}")
        print(f"    quant:  {in_det.get('quantization')}")
        if in_det['dtype'] != np.float32:
            print(f"    scale:  {in_det.get('quantization_parameters', {}).get('scales', 'N/A')}")
            print(f"    zp:     {in_det.get('quantization_parameters', {}).get('zero_points', 'N/A')}")
        print(f"  Output:")
        print(f"    shape:  {out_det['shape']}")
        print(f"    dtype:  {out_det['dtype']}")
        print(f"    quant:  {out_det.get('quantization')}")
        if out_det['dtype'] != np.float32:
            qp = out_det.get('quantization_parameters', {})
            print(f"    scale:  {qp.get('scales', 'N/A')}")
            print(f"    zp:     {qp.get('zero_points', 'N/A')}")

        # Test inference with random input
        in_data = np.random.randn(*in_det['shape']).astype(np.float32)
        if in_det['dtype'] == np.uint8:
            scale, zp = in_det['quantization']
            in_data = (in_data / scale + zp).astype(np.uint8)
        elif in_det['dtype'] == np.int8:
            scale, zp = in_det['quantization']
            in_data = (in_data / scale + zp).astype(np.int8)

        interp.set_tensor(in_det['index'], in_data)
        interp.invoke()
        out_data = interp.get_tensor(out_det['index'])
        print(f"  Test output: shape={out_data.shape} dtype={out_data.dtype}")
        print(f"    range: [{out_data.min():.4f}, {out_data.max():.4f}]")
        print(f"    flat_size: {out_data.size}")
    except Exception as e:
        print(f"\n[{name}] ERROR: {e}")
