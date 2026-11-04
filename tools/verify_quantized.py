"""Сверка моделей на кадрах с реальной ямой.

Случайные дорожные кадры дают ноль детекций выше порога — такую сверку
нельзя считать проверкой. Здесь берутся кадры, на которых яма реально
найдена (демо-ролик и тестовый кадр), и сравниваются:

  * уверенность каждой найденной ямы;
  * число детекций всех классов при низком пороге (ловим новые FP);
  * IoU рамок — сместилась ли геометрия после квантизации.

Использование:
    .model-work/tfenv/bin/python tools/verify_quantized.py \
        --reference app/src/main/assets/roadscan_yolov8.tflite \
        --candidate /tmp/int8_test.tflite \
        --frames .model-work/rep --extra /tmp/rsf/f100.png
"""
import argparse
import pathlib
import sys

import numpy as np

CONF = 0.35
LOW_CONF = 0.15


def letterbox(rgb: np.ndarray, size: int) -> np.ndarray:
    from PIL import Image

    h, w = rgb.shape[:2]
    scale = min(size / w, size / h)
    new_w, new_h = max(1, int(w * scale)), max(1, int(h * scale))
    pil = Image.fromarray(rgb).resize((new_w, new_h), Image.BILINEAR)
    resized = np.asarray(pil, dtype=np.float32)
    if resized.max() > 1.0:
        resized /= 255.0
    canvas = np.zeros((size, size, 3), dtype=np.float32)
    top, left = (size - new_h) // 2, (size - new_w) // 2
    canvas[top:top + new_h, left:left + new_w] = resized
    return canvas


def load(path: pathlib.Path):
    import tensorflow as tf

    interp = tf.lite.Interpreter(model_path=str(path))
    interp.allocate_tensors()
    return interp, interp.get_input_details()[0], interp.get_output_details()[0]


def run(interp, inp, out, batch):
    if inp["dtype"] != np.float32:
        scale, zero = inp.get("quantization", (0.0, 0))
        if scale:
            batch = (batch / scale + zero).astype(inp["dtype"])
    interp.set_tensor(inp["index"], batch)
    interp.invoke()
    res = interp.get_tensor(out["index"])
    if out["dtype"] != np.float32:
        scale, zero = out.get("quantization", (0.0, 0))
        if scale:
            res = (res.astype(np.float32) - zero) * scale
    return res


def decode(out: np.ndarray, conf: float):
    """[1,11,5376] -> список (класс, cx, cy, w, h, уверенность)."""
    data = out[0]
    channels_first = data.shape[0] < data.shape[1]
    if channels_first:
        scores, boxes, count = data[4:], data[:4], data.shape[1]
    else:
        scores, boxes, count = data[:, 4:], data[:, :4].T, data.shape[0]
    found = []
    for i in range(count):
        best = int(np.argmax(scores[:, i]))
        value = float(scores[best, i])
        if value >= conf:
            found.append((
                best,
                float(boxes[0, i]), float(boxes[1, i]),
                float(boxes[2, i]), float(boxes[3, i]),
                value,
            ))
    return found


def iou(a, b) -> float:
    left = max(a[1] - a[3] / 2, b[1] - b[3] / 2)
    right = min(a[1] + a[3] / 2, b[1] + b[3] / 2)
    top = max(a[2] - a[4] / 2, b[2] - b[4] / 2)
    bottom = min(a[2] + a[4] / 2, b[2] + b[4] / 2)
    inter = max(0.0, right - left) * max(0.0, bottom - top)
    union = a[3] * a[4] + b[3] * b[4] - inter
    return inter / union if union > 0 else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", required=True)
    ap.add_argument("--candidate", required=True)
    ap.add_argument("--frames", required=True)
    ap.add_argument("--extra", action="append", default=[],
                    help="дополнительные кадры (с ямой)")
    ap.add_argument("--size", type=int, default=512)
    args = ap.parse_args()

    paths = [pathlib.Path(p) for p in args.extra]
    paths += sorted(p for p in pathlib.Path(args.frames).rglob("*")
                    if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
    if not paths:
        raise SystemExit("нет кадров")

    ref, r_in, r_out = load(pathlib.Path(args.reference))
    cand, c_in, c_out = load(pathlib.Path(args.candidate))

    from PIL import Image

    worst_delta = 0.0
    lost_potholes, found_potholes, new_fp, pairs = 0, 0, 0, 0
    iou_values = []
    for path in paths:
        rgb = np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) / 255.0
        batch = letterbox((rgb * 255).astype(np.uint8), args.size)[None, ...]
        r_det = decode(run(ref, r_in, r_out, batch), CONF)
        c_det = decode(run(cand, c_in, c_out, batch), CONF)
        r_low = decode(run(ref, r_in, r_out, batch), LOW_CONF)
        c_low = decode(run(cand, c_in, c_out, batch), LOW_CONF)

        # FP: детекции, которых не было даже при низком пороге эталона.
        new_fp += sum(
            1 for d in c_low
            if not any(d[0] == o[0] and iou(d, o) > 0.3 for o in r_low)
        )

        for d in r_det:
            # Сопоставляем по IoU, а не по смещению центра: координаты в
            # пикселях, и допуск «0.05 px» отвергал бы совпадающие рамки.
            match = next(
                (o for o in c_det if o[0] == d[0] and iou(d, o) > 0.5),
                None,
            )
            if match is None:
                lost_potholes += 1
                print(f"  ПОТЕРЯ: {path.name} класс={d[0]} conf={d[5]:.2f}")
            else:
                found_potholes += 1
                pairs += 1
                iou_values.append(iou(d, match))
                worst_delta = max(worst_delta, abs(d[5] - match[5]))

    print(f"сохранилось: {found_potholes}, потеряно: {lost_potholes}, "
          f"новых FP (при пороге {LOW_CONF}): {new_fp}")
    if pairs:
        print(f"|Δ уверенности| макс={worst_delta:.3f}, "
              f"IoU медиана={sorted(iou_values)[len(iou_values) // 2]:.2f}")
    if lost_potholes:
        raise SystemExit("квантизация потеряла детекции — модель не применяем")
    if not pairs:
        raise SystemExit("на кадрах нет детекций — сверка не состоялась")
    print("OK: детекции сохранились")


if __name__ == "__main__":
    sys.exit(main())
