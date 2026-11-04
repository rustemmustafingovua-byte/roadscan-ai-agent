"""Полная int8-квантизация модели детекции с калибровкой на реальных кадрах.

Почему: float32-модель на 512×512 считается ~1250 мс на слабом железе —
это в десятки раз выше бюджета кадра из ТЗ (<45 мс). Динамическая квантизация
(веса int8, активности float32), которая лежит в репозитории, даёт выигрыш
в размере, но не в скорости: активности по-прежнему float32.

Полная int8-квантизация (веса И активности) ускоряет XNNPACK в 2–4 раза.
Калибровка — реальные кадры украинских роликов: распределение яркостей
асфальта должно совпадать с полем, иначе активности «уедут» и точность
упадёт. На выходе — сверка float32 против int8 на тех же кадрах:
расхождение уверенности и совпадение детекций.

Использование:
    .model-work/tfenv/bin/python tools/quantize_int8.py \
        --onnx .model-work/new/pothole_yolov8_512.onnx \
        --frames .model-work/rep \
        --out app/src/main/assets/roadscan_yolov8.tflite
"""
import argparse
import pathlib
import shutil
import sys
import tempfile

import numpy as np


def letterbox(image: np.ndarray, size: int) -> np.ndarray:
    """Кадр -> квадрат `size`×`size` с чёрными полями (как в приложении)."""
    h, w = image.shape[:2]
    scale = min(size / w, size / h)
    new_w, new_h = max(1, int(w * scale)), max(1, int(h * scale))
    resized = _resize(image, new_w, new_h)
    canvas = np.zeros((size, size, 3), dtype=np.float32)
    top = (size - new_h) // 2
    left = (size - new_w) // 2
    canvas[top:top + new_h, left:left + new_w] = resized
    return canvas


def _resize(image: np.ndarray, width: int, height: int) -> np.ndarray:
    """Билинейный ресайз без зависимости от OpenCV/PIL."""
    from PIL import Image

    pil = Image.fromarray((image * 255).astype(np.uint8) if image.dtype != np.uint8 else image)
    pil = pil.resize((width, height), Image.BILINEAR)
    out = np.asarray(pil, dtype=np.float32)
    if out.max() > 1.0:
        out /= 255.0
    return out


def build_calibration(frames_dir: pathlib.Path, size: int, limit: int) -> np.ndarray:
    """Калибровочные кадры: letterbox 512, значения 0..1 (как fillInput)."""
    from PIL import Image

    paths = sorted(
        p for p in frames_dir.rglob("*")
        if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}
    )
    if not paths:
        raise SystemExit(f"нет кадров в {frames_dir}")
    if limit:
        paths = paths[:limit]

    samples = []
    for path in paths:
        try:
            rgb = np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) / 255.0
        except Exception as exc:  # битый кадр не должен ронять квантизацию
            print(f"  пропуск {path.name}: {exc}")
            continue
        samples.append(letterbox(rgb, size))
    if not samples:
        raise SystemExit("не удалось прочитать ни один кадр")
    data = np.stack(samples).astype(np.float32)
    print(f"  калибровка: {data.shape[0]} кадров {size}×{size}, "
          f"яркость mean={data.mean():.3f} std={data.std():.3f}")
    return data


def run_onnx2tf(onnx_path: pathlib.Path, calib_path: pathlib.Path,
                workdir: pathlib.Path, input_name: str) -> pathlib.Path:
    import onnx2tf.onnx2tf as o2t

    # onnx2tf тянет тестовые картинки по сети; их URL умер — подменяем на
    # свои калибровочные (тот же трюк, что в export_tflite.py).
    o2t.download_test_image_data = lambda: np.load(calib_path)

    print("конвертация ONNX -> TFLite (полная int8-квантизация)…")
    o2t.convert(
        input_onnx_file_path=str(onnx_path),
        output_folder_path=str(workdir),
        not_use_onnxsim=True,
        batch_size=1,
        output_integer_quantized_tflite=True,
        # Формат onnx2tf: [имя_входа, путь_к_npy, mean, std]. mean/std обязаны
        # быть числами: строковые onnx2tf не приводит, и калибровка падает на
        # вычитании float32 из строки.
        custom_input_op_name_np_data_path=[
            [input_name, str(calib_path), 0.0, 1.0],
        ],
    )
    produced = sorted(workdir.glob("*integer_quant*.tflite"))
    if not produced:
        raise SystemExit("квантизация не дала tflite")
    # Приоритет: модель с float32-входом/выходом (приложение не меняется).
    # `_integer_quant.tflite` — int8 внутри, float32 на входе/выходе: приложение
    # не меняется. `_full_integer_quant.tflite` (int8 I/O) тоже подходит по
    # имени, поэтому отсекаем его явно.
    chosen = next(
        (p for p in produced
         if p.name.endswith("_integer_quant.tflite")
         and not p.name.endswith("_full_integer_quant.tflite")),
        produced[0],
    )
    for path in produced:
        print(f"  {path.name}: {path.stat().st_size / 1_000_000:.1f} МБ")
    return chosen


def describe(path: pathlib.Path) -> None:
    import tensorflow as tf

    interp = tf.lite.Interpreter(model_path=str(path))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    print(f"  вход : shape={inp['shape']} dtype={inp['dtype'].__name__}")
    print(f"  выход: shape={out['shape']} dtype={out['dtype'].__name__}")


def verify(float_model: pathlib.Path, int8_model: pathlib.Path,
           frames_dir: pathlib.Path, size: int, conf: float = 0.35) -> None:
    """Сверка эталона против int8: состав детекций и уверенность.

    Сопоставление — по классу и положению центра (±5% кадра): ящики после
    квантизации смещаются на доли пикселя, и точное совпадение координат
    ничего бы не сказало о качестве.
    """
    import tensorflow as tf
    from PIL import Image

    def load(model_path):
        interp = tf.lite.Interpreter(model_path=str(model_path))
        interp.allocate_tensors()
        return interp, interp.get_input_details()[0], interp.get_output_details()[0]

    f_interp, f_in, f_out = load(float_model)
    q_interp, q_in, q_out = load(int8_model)

    def run(interp, inp, out, batch):
        interp.set_tensor(inp["index"], batch)
        interp.invoke()
        return interp.get_tensor(out["index"])

    paths = sorted(p for p in frames_dir.rglob("*")
                   if p.suffix.lower() in {".jpg", ".jpeg", ".png"})[:40]
    score_deltas, matched, only_float, only_int8 = [], 0, 0, 0
    for path in paths:
        rgb = np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) / 255.0
        batch = letterbox(rgb, size)[None, ...].astype(np.float32)
        if q_in["dtype"] != np.float32:
            scale = q_in.get("quantization", (0.0, 0))[0]
            zero = q_in.get("quantization", (0, 0))[1]
            batch = (batch / scale + zero).astype(q_in["dtype"])
        f_res = run(f_interp, f_in, f_out, batch)
        q_res = run(q_interp, q_in, q_out, batch)
        if q_out["dtype"] != np.float32:
            scale, zero = q_out.get("quantization", (0.0, 0))
            q_res = (q_res.astype(np.float32) - zero) * scale

        f_det = _decode(f_res, conf)
        q_det = _decode(q_res, conf)
        remaining = list(q_det)
        for item in f_det:
            hit = next(
                (c for c in remaining
                 if c[0] == item[0] and abs(c[1] - item[1]) <= 0.05),
                None,
            )
            if hit is not None:
                matched += 1
                remaining.remove(hit)
                score_deltas.append(abs(item[2] - hit[2]))
            else:
                only_float += 1
        only_int8 += len(remaining)
        if f_det and q_det:
            score_deltas.append(abs(max(s for _, _, s in f_det) -
                                    max(s for _, _, s in q_det)))

    print(f"  детекций float32/int8: совпало {matched}, "
          f"только float32 {only_float}, только int8 {only_int8}")
    if score_deltas:
        print(f" |maxΔ уверенности лучшей: {max(score_deltas):.3f} "
              f"(средняя {sum(score_deltas) / len(score_deltas):.3f})")
    if matched == 0 and (only_float or only_int8):
        raise SystemExit("квантизация изменила состав детекций — модель не меняем")


def _decode(out: np.ndarray, conf: float):
    """Детекции YOLOv8 [1,11,5376] -> множество (класс, якорь)."""
    data = out[0]
    channels_first = data.shape[0] < data.shape[1]
    if channels_first:
        scores = data[4:, :]
        boxes = data[:4, :]
        count = data.shape[1]
    else:
        scores = data[:, 4:]
        boxes = data[:, :4].T
        count = data.shape[0]
    found = []
    for i in range(count):
        best = int(np.argmax(scores[:, i]))
        value = float(scores[best, i])
        if value >= conf:
            found.append((best, float(boxes[1, i]), value))
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx", required=True)
    ap.add_argument("--frames", required=True, help="каталог калибровочных кадров")
    ap.add_argument("--out", required=True)
    ap.add_argument("--input-name", default="images")
    ap.add_argument("--limit", type=int, default=200)
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--reference", default="app/src/main/assets/roadscan_yolov8.tflite",
                    help="модель-эталон для сверки (текущая в ассетах)")
    ap.add_argument("--keep-temp", action="store_true")
    ap.add_argument("--no-verify", action="store_true")
    args = ap.parse_args()

    onnx_path = pathlib.Path(args.onnx).resolve()
    frames_dir = pathlib.Path(args.frames).resolve()
    out_path = pathlib.Path(args.out).resolve()
    if not onnx_path.exists():
        raise SystemExit(f"нет ONNX: {onnx_path}")

    workdir = pathlib.Path(tempfile.mkdtemp(prefix="tflite_int8_"))
    try:
        calib = build_calibration(frames_dir, args.size, args.limit)
        calib_path = workdir / "calib.npy"
        np.save(calib_path, calib)

        float_path = pathlib.Path(args.reference).resolve()
        if not float_path.exists():
            raise SystemExit(f"нет модели-эталона: {float_path}")
        print(f"эталон для сверки: {float_path}")

        quantized = run_onnx2tf(onnx_path, calib_path, workdir, args.input_name)
        print("проверка квантованной модели:")
        describe(quantized)

        if not args.no_verify:
            verify(float_path, quantized, frames_dir, args.size)

        out_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(quantized, out_path)
        size_mb = out_path.stat().st_size / 1_000_000
        print(f"сохранено: {out_path} ({size_mb:.1f} МБ)")
    finally:
        if not args.keep_temp:
            shutil.rmtree(workdir, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
