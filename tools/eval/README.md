# YOLOv8n COCO val 评估工具

用 PC 对 `yolov8n.onnx` 模型跑 COCO val2017 推理，可视化检测结果。

## 用法

```powershell
cd tools/eval

# 安装依赖
pip install onnxruntime opencv-python numpy

# 1. 下载 COCO val2017 (1GB)
#    http://images.cocodataset.org/zips/val2017.zip
#    放到 F:\downd\IDMdown\val2017.zip（或修改 eval_coco.py 里的 COCO_ZIP_PATH）

# 2. 运行评测（全量 5000 张）
python eval_coco.py
```

结果输出在 `output/` 文件夹。
