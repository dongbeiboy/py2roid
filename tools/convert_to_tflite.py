#!/usr/bin/env python3
"""ONNX → TFLite 模型转换工具。

两种方式：
  1. ultralytics 原生导出（推荐，需要 .pt 权重）
  2. onnx2tf 转换（从 .onnx 出发，需要安装 onnx2tf）

用法:
  python convert_to_tflite.py --onnx model.onnx --output model.tflite
  python convert_to_tflite.py --pt yolov8n.pt --output model.tflite --imgsz 640
"""

import argparse
import sys
from pathlib import Path


def convert_via_ultralytics(pt_path: str, output: str, imgsz: int = 640):
    """使用 ultralytics 原生导出（需要 .pt 权重）"""
    try:
        from ultralytics import YOLO
    except ImportError:
        print("请安装 ultralytics: pip install ultralytics")
        sys.exit(1)

    model = YOLO(pt_path)
    model.export(format="tflite", imgsz=imgsz, half=False)
    
    # ultralytics 导出到同目录，重命名
    src = Path(pt_path).with_suffix(".tflite")
    if src.exists():
        src.rename(output)
        print(f"✓ 导出成功: {output}")
    else:
        tflite_files = list(Path(pt_path).parent.glob("*.tflite"))
        if tflite_files:
            tflite_files[0].rename(output)
            print(f"✓ 导出成功: {output}")
        else:
            print("✗ 导出失败，未找到输出文件")


def convert_via_onnx2tf(onnx_path: str, output: str):
    """使用 onnx2tf 从 ONNX 转换"""
    try:
        import onnx2tf
    except ImportError:
        print("请安装 onnx2tf: pip install onnx2tf")
        sys.exit(1)

    import subprocess
    cmd = [
        sys.executable, "-m", "onnx2tf",
        "-i", onnx_path,
        "-o", str(Path(output).with_suffix("")),
        "-osd",  # 保持输出 shape 不变
        "-kat", "input",  # 保持输入 shape
    ]
    subprocess.run(cmd, check=True)

    # onnx2tf 输出到目录，里面有个 .tflite
    out_dir = Path(output).with_suffix("")
    tflite_file = out_dir / "model_float32.tflite"
    if tflite_file.exists():
        tflite_file.rename(output)
        print(f"✓ 转换成功: {output}")
    else:
        # 搜索所有 .tflite
        found = list(out_dir.rglob("*.tflite"))
        if found:
            found[0].rename(output)
            print(f"✓ 转换成功: {output}")
        else:
            print(f"✗ 转换失败，{out_dir} 中未找到 .tflite")


def main():
    parser = argparse.ArgumentParser(description="ONNX → TFLite 模型转换")
    parser.add_argument("--onnx", help="输入 .onnx 文件")
    parser.add_argument("--pt", help="输入 .pt 权重文件（使用 ultralytics 导出）")
    parser.add_argument("--output", default="model.tflite", help="输出 .tflite 文件")
    parser.add_argument("--imgsz", type=int, default=640, help="输入尺寸")
    args = parser.parse_args()

    if args.pt:
        convert_via_ultralytics(args.pt, args.output, args.imgsz)
    elif args.onnx:
        convert_via_onnx2tf(args.onnx, args.output)
    else:
        print("请指定 --onnx 或 --pt")
        sys.exit(1)


if __name__ == "__main__":
    main()
