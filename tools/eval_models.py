"""Сравнение моделей дефектов дороги на украинских кадрах.

Наборы:
  * positives — кадры с настоящей ямой (ожидаем детект��ю);
  * negatives — кропы чистого асфальта (ожидаем НОЛЬ детекций).

Метрики: полнота (recall) по положительным и ложные срабатывания (FP)
на отрицательных. Каждая модель прогоняется через ultralytics — тот же
препроцессинг (letterbox 640), что и в приложении.

Запуск:
    .venv/bin/python tools/eval_models.py --frames DIR --models a.pt,b.pt
"""
import argparse
import json
import pathlib
import time

from ultralytics import YOLO


def iou(a, b):
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    ua = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
    return inter / ua if ua > 0 else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", required=True, help="каталог: positives/ и negatives/")
    ap.add_argument("--models", required=True, help="список .pt через запятую")
    ap.add_argument("--conf", type=float, default=0.35)
    ap.add_argument("--iou", type=float, default=0.5)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    root = pathlib.Path(args.frames)
    positives = sorted((root / "positives").glob("*.jpg")) + sorted((root / "positives").glob("*.png"))
    negatives = sorted((root / "negatives").glob("*.jpg")) + sorted((root / "negatives").glob("*.png"))
    if not positives or not negatives:
        print(f"нужны positives/ и negatives/ в {root}", flush=True)
        return 2

    print(f"позитивов: {len(positives)}  негативов: {len(negatives)}\n")
    report = {}

    for model_path in args.models.split(","):
        model_path = model_path.strip()
        if not model_path:
            continue
        print("=" * 70)
        print(model_path)
        try:
            model = YOLO(model_path)
        except Exception as exc:  # noqa: BLE001
            print("  НЕ ЗАГРУЗИЛАСЬ:", str(exc)[:140])
            continue
        names = model.names if isinstance(model.names, dict) else dict(enumerate(model.names))
        print("  классы:", names)

        # Ожидаемая рамка ямы на позитивах (задаётся GT-боксом из файла .txt рядом).
        hits = 0
        detected_pos = 0
        fp_neg = 0
        t0 = time.time()
        n_frames = 0

        for img in positives:
            gt = _gt_for(img)
            res = model.predict(str(img), conf=args.conf, imgsz=args.imgsz, verbose=False, device="mps")[0]
            n_frames += 1
            boxes = [list(map(float, b.xyxy[0])) for b in res.boxes]
            if boxes:
                detected_pos += 1
            if gt is not None and any(iou(gt, b) >= args.iou for b in boxes):
                hits += 1

        for img in negatives:
            res = model.predict(str(img), conf=args.conf, imgsz=args.imgsz, verbose=False, device="mps")[0]
            n_frames += 1
            fp_neg += len(res.boxes)

        elapsed = time.time() - t0
        recall = hits / len(positives)
        fp_rate = fp_neg / len(negatives)
        print(f"  полнота (IoU>={args.iou}): {hits}/{len(positives)} = {recall:.0%}")
        print(f"  кадров с детекцией на позитивах: {detected_pos}/{len(positives)}")
        print(f"  ложных срабатываний на негативах: {fp_neg} (на {len(negatives)} кадров)")
        print(f"  скорость: {n_frames / max(elapsed, 0.001):.1f} кадр/с (MPS)")

        report[model_path] = {
            "classes": {str(k): v for k, v in names.items()},
            "recall": round(recall, 3),
            "frames_with_detection": f"{detected_pos}/{len(positives)}",
            "false_positives": fp_neg,
            "fps_mps": round(n_frames / max(elapsed, 0.001), 1),
        }

    if args.out:
        pathlib.Path(args.out).write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"\nотчёт: {args.out}")
    return 0


def _gt_for(img: pathlib.Path):
    """GT-рамка из одноимённого .txt: class x1 y1 x2 y2 (в пикселях кадра)."""
    txt = img.with_suffix(".txt")
    if not txt.exists():
        return None
    for line in txt.read_text().splitlines():
        parts = line.split()
        if len(parts) != 5:
            continue
        _, x1, y1, x2, y2 = parts
        return [float(x1), float(y1), float(x2), float(y2)]
    return None


if __name__ == "__main__":
    raise SystemExit(main())
