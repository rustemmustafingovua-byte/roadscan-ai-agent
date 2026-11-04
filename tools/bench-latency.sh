#!/usr/bin/env bash
# Замер кадрового бюджета на устройстве/эмуляторе.
#
# Печатает из лога приложения строки RoadScanPerf (пишутся раз в 30 кадров):
# total — полная обработка кадра, infer — один инференс модели, backend —
# XNNPACK/NNAPI. По этим трём числам видно, где уходит бюджет ТЗ (<45 мс).
#
# Использование: tools/bench-latency.sh [секунд_наблюдения]
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG="ai.roadscan.app"
SECONDS_TO_WATCH="${1:-40}"

if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "нет устройства/эмулятора (adb get-state)" >&2
    exit 1
fi

"$ADB" logcat -c

# Смена нужна: без неё анализатор кадров не запущен и лога не будет.
"$ADB" shell am force-stop "$PKG" >/dev/null
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 7
KEY="$(cat /tmp/rs-key.txt 2>/dev/null | tr -d '\r\n' || true)"
if [ -n "$KEY" ]; then
    "$ADB" shell am start -a android.intent.action.VIEW \
        -d "roadscan://license?key=$KEY" "$PKG" >/dev/null 2>&1 || true
    sleep 3
fi
"$ADB" shell input tap 127 2274 >/dev/null   # вкладка «Сканування»
sleep 2
"$ADB" shell input tap 582 2027 >/dev/null   # «Почати зміну»

echo "наблюдение ${SECONDS_TO_WATCH} с…"
sleep "$SECONDS_TO_WATCH"

LOG="$("$ADB" logcat -d -s RoadScanPerf 2>/dev/null | grep -a 'frame=' || true)"
if [ -z "$LOG" ]; then
    echo "нет строк RoadScanPerf — смена не запустилась?" >&2
    exit 2
fi

echo "$LOG" | sed 's/.*RoadScanPerf: //'

echo "$LOG" | sed 's/.*RoadScanPerf: //' | python3 -c '
import re, statistics, sys

totals, infers, backends = [], [], []
for line in sys.stdin:
    total = re.search(r"total=([\d.]+)ms", line)
    infer = re.search(r"infer=([\d.]+)ms", line)
    backend = re.search(r"backend=(\w+)", line)
    if total:
        totals.append(float(total.group(1)))
    if infer:
        infers.append(float(infer.group(1)))
    if backend:
        backends.append(backend.group(1))

def stats(name, values):
    if not values:
        print(f"{name}: нет данных")
        return
    values.sort()
    p90 = values[min(len(values) - 1, int(len(values) * 0.9))]
    print(f"{name}: медиана {statistics.median(values):.0f} мс, "
          f"мин {values[0]:.0f}, макс {values[-1]:.0f}, p90 {p90:.0f} мс "
          f"(кадров {len(values)})")

stats("весь кадр ", totals)
stats("инференс  ", infers)
if backends:
    print(f"бэкенд: {backends[-1]}")

if totals:
    budget = 45.0
    median = statistics.median(totals)
    verdict = "В БЮДЖЕТЕ" if median <= budget else f"ВЫШЕ БЮДЖЕТА {budget:.0f} мс"
    print(f"цель ТЗ <{budget:.0f} мс: {verdict}")
'
