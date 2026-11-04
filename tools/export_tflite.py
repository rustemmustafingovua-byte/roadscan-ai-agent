"""Экспорт модели детекции в TFLite (ONNX -> TFLite) с проверкой результата.

Использование:
    .model-work/tfenv/bin/python tools/export_tflite.py \
        --onnx .model-work/new/pothole_yolov8_512.onnx \
        --out  app/src/main/assets/roadscan_yolov8.tflite \
        --labels app/src/main/assets/labels.txt

Скрипт:
  * обходит падение onnx2tf (его URL с тестовыми данными больше не отдаёт
    файл — приходит XML-ошибка 404, и numpy падает на np.load);
  * печатает вход/выход полученной модели, чтобы сверить с ожиданиями
    детектора в приложении (NCHW/NHWC, число каналов = 4 + классы).
"""
import argparse
import pathlib
import shutil
import sys
import tempfile

import numpy as np


def patch_onnx2tf_test_data():
    """Подменяет загрузку тестовых данных onnx2tf на случайные."""
    import onnx2tf.onnx2tf as o2t

    def fake_test_data():
        rng = np.random.default_rng(42)
        return rng.random((20, 128, 128, 3), dtype=np.float32)

    o2t.download_test_image_data = fake_test_data


def describe_tflite(path: pathlib.Path):
    import tensorflow as tf

    interp = tf.lite.Interpreter(model_path=str(path))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    print(f"  вход : {inp['name']} shape={inp['shape']} dtype={inp['dtype'].__name__}")
    print(f"  выход: {out['name']} shape={out['shape']} dtype={out['dtype'].__name__}")
    size_mb = path.stat().st_size / 1_000_000
    print(f"  размер: {size_mb:.1f} МБ")
    return inp, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--labels", default="")
    ap.add_argument("--opset", type=int, default=13)
    ap.add_argument("--keep-temp", action="store_true")
    args = ap.parse_args()

    onnx_path = pathlib.Path(args.onnx).resolve()
    out_path = pathlib.Path(args.out).resolve()
    if not onnx_path.exists():
        print(f"нет ONNX: {onnx_path}", file=sys.stderr)
        return 2

    patch_onnx2tf_test_data()

    import onnx2tf.onnx2tf as o2t

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="tflite_export_"))
    print(f"конвертация {onnx_path.name} -> {tmp}")
    o2t.convert(
        input_onnx_file_path=str(onnx_path),
        output_folder_path=str(tmp),
        not_use_onnxsim=True,
        batch_size=1,
        output_integer_quantized_tflite=False,
    )

    produced = sorted(tmp.glob("*.tflite"))
    if not produced:
        print("конвертация не дала tflite", file=sys.stderr)
        return 3
    # onnx2tf кладёт несколько вариантов (float32/float16) — берём float32.
    chosen = next((p for p in produced if "float16" not in p.name), produced[0])
    print(f"  получен: {chosen.name}")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(chosen, out_path)
    print(f"сохранено: {out_path}")
    print("проверка:")
    describe_tflite(out_path)

    if args.labels:
        labels = pathlib.Path(args.labels)
        if labels.exists():
            names = [line.strip() for line in labels.read_text(encoding="utf-8").splitlines() if line.strip()]
            print(f"  классов в labels.txt: {len(names)}")
            print(f"  классы: {names}")

    if not args.keep_temp:
        shutil.rmtree(tmp, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
