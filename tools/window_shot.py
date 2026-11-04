"""Снимок окна приложения по идентификатору (CoreGraphics), без переключения фокуса.

Использование:
    python3 tools/window_shot.py Telegram /tmp/tg.png
"""
import subprocess
import sys

import Quartz


def find_window(app_name):
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
        bounds = w.get("kCGWindowBounds")
        area = bounds.get("Width", 0) * bounds.get("Height", 0) if bounds else 0
        if area < 20000:
            continue
        if best is None or area > best[0]:
            best = (area, w.get("kCGWindowNumber"), owner, bounds)
    return best


def main():
    if len(sys.argv) < 3:
        print("usage: window_shot.py <App> <out.png>", file=sys.stderr)
        return 2
    app, out = sys.argv[1], sys.argv[2]
    found = find_window(app)
    if not found:
        print(f"окно {app} не найдено", file=sys.stderr)
        return 1
    _, win_id, owner, bounds = found
    print(f"{owner} window={win_id} {bounds}")
    subprocess.run(["screencapture", "-x", "-o", f"-l{win_id}", out], check=True)
    print(f"сохранено: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
