"""Клик и ввод в окне приложения по координатам СНИМКА (не окна).

Снимок окна может быть масштабирован и сдвинут, поэтому координаты
пересчитываются по фактическому размеру PNG и границам окна.

Использование:
    python3 tools/tg_ui.py click Telegram /tmp/tg.png 288 60
    python3 tools/tg_ui.py type  Telegram /tmp/tg.png "текст"
"""
import subprocess
import sys

import Quartz


def window_bounds(app_name):
    windows = Quartz.CGWindowListCopyWindowInfo(
        Quartz.kCGWindowListOptionOnScreenOnly | Quartz.kCGWindowListExcludeDesktopElements,
        Quartz.kCGNullWindowID,
    )
    best = None
    for w in windows:
        owner = w.get("kCGWindowOwnerName") or ""
        if app_name.lower() not in owner.lower():
            continue
        if w.get("kCGWindowLayer") != 0:
            continue
        b = w.get("kCGWindowBounds") or {}
        area = b.get("Width", 0) * b.get("Height", 0)
        if area < 20000:
            continue
        if best is None or area > best[0]:
            best = (area, w.get("kCGWindowNumber"), b)
    return best


def shot(app_name, out):
    subprocess.run(
        ["python3", "/Users/admin/workspace/roadscan-ai-agent/tools/window_shot.py", app_name, out],
        capture_output=True,
    )


def to_window_px(app_name, png_path, ix, iy):
    from PIL import Image

    with Image.open(png_path) as im:
        pw, ph = im.size
    found = window_bounds(app_name)
    if not found:
        raise SystemExit(f"окно {app_name} не найдено")
    _, _, b = found
    sx = b.get("Width", 0) / pw
    sy = b.get("Height", 0) / ph
    return int(b.get("X", 0) + ix * sx), int(b.get("Y", 0) + iy * sy)


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        return 2
    action, app, png = sys.argv[1], sys.argv[2], sys.argv[3]
    coords = sys.argv[4:]
    ix, iy = int(coords[0]), int(coords[1])
    wx, wy = to_window_px(app, png, ix, iy)

    if action == "click":
        cmd = ["peekaboo", "click", "--app", app, "--at", f"{wx},{wy}", "--foreground"]
        if len(coords) > 2:
            cmd += ["--text", coords[2]]
    elif action == "type":
        cmd = ["peekaboo", "type", "--app", app, coords[2], "--foreground"]
    elif action == "key":
        cmd = ["peekaboo", "press", "--app", app, coords[2], "--foreground"]
    else:
        raise SystemExit("неизвестное действие")

    subprocess.run(["osascript", "-e", f'tell application "{app}" to activate'], capture_output=True)
    import time

    time.sleep(1.5)
    res = subprocess.run(cmd, capture_output=True, text=True)
    print(f"{action}: {wx},{wy} ->", (res.stdout or res.stderr).strip().splitlines()[-1:])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
