"""Замер качества модели RoadScan на реальных украинских роликах.

Скрипт гоняет ту же модель (ONNX-экспорт той же сети, что лежит в assets)
по видео из набора statecontrol/storage/media и печатает статистику:
сколько дефектов найдено, с какими классами и уверенностью.

Запуск:
    .venv/bin/python tools/bench_uk_roads.py --videos DIR [--limit N] [--out JSON]
"""
import argparse
import json
import pathlib
import sys
import time

VIDEO_EXTS = {".mp4", ".webm", ".mov", ".mkv"}


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("--videos", required=True, help="каталог с роликами")
    ap.add_argument("--weights", default="yolov8n.pt")
    ap.add_argument("--onnx", default="detect.onnx", help="ONNX-экспорт (приоритетнее .pt)")
    ap.add_argument("--limit", type=int, default=0, help="сколько роликов обработать (0 — все)")
    ap.add_argument("--conf", type=float, default=0.35)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--stride", type=int, default=10, help="раз в сколько кадров брать")
    ap.add_argument("--out", default="")
    return ap.parse_args()


def main():
    args = parse_args()
    try:
        from ultralytics import YOLO
    except ImportError:
        print("нужен ultralytics: pip install ultralytics", file=sys.stderr)
        return 2

    root = pathlib.Path(args.videos)
    videos = sorted(p for p in root.rglob("*") if p.suffix.lower() in VIDEO_EXTS)
    if args.limit:
        videos = videos[: args.limit]
    if not videos:
        print(f"видео не найдены в {root}", file=sys.stderr)
        return 2

    model_path = args.onnx if args.onnx and pathlib.Path(args.onnx).exists() else args.weights
    print(f"модель: {model_path}")
    model = YOLO(model_path, task="detect")
    names = model.names if isinstance(model.names, dict) else dict(enumerate(model.names))

    total_defects = 0
    total_frames = 0
    total_sampled = 0
    per_class = {}
    per_video = []
    confs = []
    t0 = time.time()

    for path in videos:
        try:
            results = model.predict(
                source=str(path),
                conf=args.conf,
                imgsz=args.imgsz,
                vid_stride=args.stride,
                verbose=False,
                device="mps" if _has_mps() else "cpu",
            )
        except Exception as exc:  # noqa: BLE001 — битые файлы не должны ронять замер
            per_video.append({"file": path.name, "error": str(exc)[:160]})
            continue

        v_defects = 0
        v_conf = []
        for res in results:
            total_sampled += 1
            total_frames += 1
            for box in res.boxes:
                v_defects += 1
                cls = names.get(int(box.cls[0]), str(int(box.cls[0])))
                per_class[cls] = per_class.get(cls, 0) + 1
                conf = float(box.conf[0])
                v_conf.append(conf)
                confs.append(conf)
        total_defects += v_defects
        per_video.append(
            {
                "file": path.name,
                "frames": len(results),
                "defects": v_defects,
                "max_conf": round(max(v_conf), 3) if v_conf else None,
            }
        )
        print(f"  {path.name:44s} кадров={len(results):4d} дефектів={v_defects:3d}")

    elapsed = time.time() - t0
    print()
    print(f"роликів:            {len(per_video)}")
    print(f"кадрів проаналізовано: {total_sampled}")
    print(f"детектів:           {total_defects}")
    if confs:
        confs_sorted = sorted(confs)
        print(f"уверенность:        min={confs_sorted[0]:.2f} "
              f"медіана={confs_sorted[len(confs_sorted)//2]:.2f} "
              f"max={confs_sorted[-1]:.2f}")
    print(f"по класах:          {per_class}")
    print(f"час:                {elapsed:.1f} с "
          f"({total_sampled / max(elapsed, 0.001):.1f} кадр/с)")

    if args.out:
        pathlib.Path(args.out).write_text(
            json.dumps(
                {
                    "videos": len(per_video),
                    "frames": total_sampled,
                    "detections": total_defects,
                    "per_class": per_class,
                    "per_video": per_video,
                    "seconds": round(elapsed, 1),
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"отчёт: {args.out}")
    return 0


def _has_mps():
    try:
        import torch

        return bool(torch.backends.mps.is_available())
    except Exception:  # noqa: BLE001
        return False


if __name__ == "__main__":
    raise SystemExit(main())
