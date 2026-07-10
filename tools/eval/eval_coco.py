"""
YOLOv8n ONNX COCO val2017 推理可视化工具
下载完整 COCO val2017 (1GB, 5000张)，抽 N 张跑推理后画框保存。
"""

import os
import random
import zipfile
import numpy as np
import cv2
import onnxruntime as ort
from pathlib import Path

# ── 配置 ──────────────────────────────────────────────────
MODEL_PATH = os.path.join("..", "..", "android", "app", "src", "main", "assets", "models", "yolov8n.onnx")
COCO_ZIP_PATH = R"F:\downd\IDMdown\val2017.zip"  # 本地 zip 路径
COCO_DIR = "coco_images"
OUTPUT_DIR = "output"
NUM_IMAGES = 5000      # 全量 5000 张
CONF_THRESHOLD = 0.5

# COCO 80 类
COCO_CLASSES = [
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
    "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
    "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
    "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
    "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
    "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
    "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
    "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
    "toothbrush",
]


# ── 数据集下载/解压 ─────────────────────────────────────

def extract_local_zip():
    """从本地 val2017.zip 解压"""
    zip_path = Path(COCO_ZIP_PATH)
    img_dir = Path(COCO_DIR) / "val2017"

    if img_dir.exists() and len(list(img_dir.iterdir())) > 0:
        print(f"  Already extracted: {img_dir} ({len(list(img_dir.iterdir()))} files)")
        return str(img_dir)

    if not zip_path.exists():
        print(f"✗ Local zip not found: {zip_path}")
        raise FileNotFoundError(f"val2017.zip not found at {zip_path}")

    print("Extracting val2017.zip ...")
    with zipfile.ZipFile(zip_path, 'r') as zf:
        zf.extractall(COCO_DIR)
    print(f"  Extracted to {img_dir}")
    return str(img_dir)


# ── 预处理（与 py2roid ImagePreprocessor 一致）────────────

def letterbox(im, new_shape=(640, 640), color=(114, 114, 114)):
    shape = im.shape[:2]
    r = min(new_shape[0] / shape[0], new_shape[1] / shape[1])
    new_unpad = (int(round(shape[1] * r)), int(round(shape[0] * r)))
    im = cv2.resize(im, new_unpad, interpolation=cv2.INTER_LINEAR)
    dw = new_shape[1] - new_unpad[0]
    dh = new_shape[0] - new_unpad[1]
    top = dh // 2
    bottom = dh - top
    left = dw // 2
    right = dw - left
    im = cv2.copyMakeBorder(im, top, bottom, left, right, cv2.BORDER_CONSTANT, value=color)
    return im, r, left, top


def preprocess(image_path):
    orig = cv2.imread(image_path)
    if orig is None:
        return None, None
    orig_h, orig_w = orig.shape[:2]
    rgb = cv2.cvtColor(orig, cv2.COLOR_BGR2RGB)
    padded, scale, pad_left, pad_top = letterbox(rgb)
    blob = padded.transpose(2, 0, 1).astype(np.float32) / 255.0
    blob = np.expand_dims(blob, axis=0)
    meta = {"scale": scale, "pad_left": pad_left, "pad_top": pad_top,
            "orig_w": orig_w, "orig_h": orig_h, "path": image_path}
    return blob, meta


# ── YOLOv8 后处理 ──────────────────────────────────────────

def xywh2xyxy(x, pad_left, pad_top, scale, orig_w, orig_h):
    cx, cy, w, h = x
    cx_orig = (cx - pad_left) / scale
    cy_orig = (cy - pad_top) / scale
    x1 = max(0, min(1, (cx_orig - w / 2) / orig_w))
    y1 = max(0, min(1, (cy_orig - h / 2) / orig_h))
    x2 = max(0, min(1, (cx_orig + w / 2) / orig_w))
    y2 = max(0, min(1, (cy_orig + h / 2) / orig_h))
    return x1, y1, x2, y2


def nms(boxes, scores, iou_threshold=0.45):
    if len(boxes) == 0:
        return []
    indices = np.argsort(-scores)
    keep = []
    while len(indices) > 0:
        i = indices[0]
        keep.append(i)
        if len(indices) == 1:
            break
        iou = np.array([box_iou(boxes[i], boxes[j]) for j in indices[1:]])
        indices = indices[1:][iou <= iou_threshold]
    return keep


def box_iou(a, b):
    x1 = max(a[0], b[0])
    y1 = max(a[1], b[1])
    x2 = min(a[2], b[2])
    y2 = min(a[3], b[3])
    inter = max(0, x2 - x1) * max(0, y2 - y1)
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    return inter / (area_a + area_b - inter + 1e-6)


def draw_detections(image_path, detections, output_path):
    img = cv2.imread(image_path)
    h, w = img.shape[:2]
    colors = [
        (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0),
        (255, 0, 255), (0, 255, 255), (128, 0, 0), (0, 128, 0),
    ]
    for det in detections:
        x1, y1, x2, y2 = det["box"]
        cls_name = det["class"]
        conf = det["score"]
        color = colors[det["class_id"] % len(colors)]
        px1 = int(x1 * w)
        py1 = int(y1 * h)
        px2 = int(x2 * w)
        py2 = int(y2 * h)
        cv2.rectangle(img, (px1, py1), (px2, py2), color, 2)
        label = f"{cls_name} {conf:.2f}"
        (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)
        cv2.rectangle(img, (px1, py1 - th - 4), (px1 + tw + 4, py1), color, -1)
        cv2.putText(img, label, (px1 + 2, py1 - 2),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)
    cv2.imwrite(output_path, img)


# ── 主逻辑 ────────────────────────────────────────────────

def main():
    print("=" * 60)
    print("YOLOv8n COCO val2017 Evaluation")
    print("=" * 60)

    # 1. 解压本地 zip
    img_dir = extract_local_zip()
    all_images = sorted(Path(img_dir).glob("*.jpg"))
    print(f"  Total images available: {len(all_images)}")

    random.Random(42).shuffle(all_images)
    selected = all_images[:NUM_IMAGES]
    print(f"  Selected: {len(selected)} images")

    # 2. 加载模型
    print(f"\nLoading model: {MODEL_PATH}")
    if not os.path.exists(MODEL_PATH):
        print(f"✗ Model not found at {MODEL_PATH}")
        return
    session = ort.InferenceSession(MODEL_PATH)
    input_name = session.get_inputs()[0].name
    print(f"  Input: {input_name} {session.get_inputs()[0].shape}")
    print(f"  Output: {session.get_outputs()[0].name} {session.get_outputs()[0].shape}")

    # 3. 逐张推理
    out_dir = Path(OUTPUT_DIR)
    out_dir.mkdir(exist_ok=True)

    total_detections = 0
    total_images_with_det = 0
    total_inf_time = 0

    for idx, img_path in enumerate(selected):
        fname = img_path.name
        tensor, meta = preprocess(str(img_path))
        if tensor is None:
            print(f"  [{idx+1}/{len(selected)}] ✗ {fname} — failed to read")
            continue

        t0 = cv2.getTickCount()
        outputs = session.run(None, {input_name: tensor})
        t1 = cv2.getTickCount()
        inf_ms = (t1 - t0) / cv2.getTickFrequency() * 1000
        total_inf_time += inf_ms

        output = outputs[0]
        num_proposals = output.shape[2]
        raw = []
        for i in range(num_proposals):
            cx = output[0, 0, i]
            cy = output[0, 1, i]
            w = output[0, 2, i]
            h = output[0, 3, i]
            max_prob = 0
            max_cls = 0
            for c in range(80):
                prob = output[0, 4 + c, i]
                if prob > max_prob:
                    max_prob = prob
                    max_cls = c
            if max_prob < CONF_THRESHOLD:
                continue
            x1, y1, x2, y2 = xywh2xyxy(
                (cx, cy, w, h),
                meta["pad_left"], meta["pad_top"],
                meta["scale"], meta["orig_w"], meta["orig_h"]
            )
            raw.append({
                "box": [x1, y1, x2, y2],
                "score": float(max_prob),
                "class_id": max_cls,
                "class": COCO_CLASSES[max_cls],
            })

        if raw:
            boxes = np.array([d["box"] for d in raw])
            scores = np.array([d["score"] for d in raw])
            keep = nms(boxes, scores)
            detections = [raw[i] for i in keep]
        else:
            detections = []

        out_path = out_dir / fname
        draw_detections(str(img_path), detections, str(out_path))

        total_detections += len(detections)
        if len(detections) > 0:
            total_images_with_det += 1

        top_str = f"top={detections[0]['class']}({detections[0]['score']:.2f})" if detections else "none"
        print(f"  [{idx+1}/{len(selected)}] {fname} → {len(detections)} dets {top_str} [{inf_ms:.0f}ms]")

    # 4. 汇总
    print("\n" + "=" * 60)
    print("Summary")
    print(f"  Images evaluated: {len(selected)}")
    print(f"  Images with detections: {total_images_with_det} ({total_images_with_det/len(selected)*100:.0f}%)")
    print(f"  Total objects detected: {total_detections}")
    print(f"  Avg objects per image: {total_detections/len(selected):.1f}")
    print(f"  Avg inference time: {total_inf_time/len(selected):.0f}ms")
    print(f"  Output: {OUTPUT_DIR}/")
    print("=" * 60)


if __name__ == "__main__":
    main()
