#!/bin/bash
# Проверка offline-first и сохранности данных при убийстве процесса.
# Сценарий: смена без интернета -> принудительное закрытие -> смена в списке.
set -u
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
PKG=ai.roadscan.app

say() { echo; echo "=== $* ==="; }

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
}

tap_exact() {
  dump_ui
  python3 - "$1" <<'PY'
import sys, re, subprocess
target = sys.argv[1]
try:
    d = open('/tmp/ui.xml', encoding='utf-8').read()
except Exception:
    raise SystemExit(1)
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', d):
    if m.group(1) == target:
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        subprocess.run(['adb', 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)])
        raise SystemExit(0)
raise SystemExit(1)
PY
}

say "1. Выключаем сеть (режим поля)"
adb shell svc wifi disable >/dev/null 2>&1
adb shell svc data disable >/dev/null 2>&1
adb shell settings put global airplane_mode_on 1 >/dev/null 2>&1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1
echo "  сеть выключена"

say "2. Смена без интернета"
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 6
adb shell input tap 127 2274; sleep 2           # Сканирование
tap_exact "Почати зміну" || echo "  ! не нажата кнопка старта"
sleep 15
tap_exact "Завершити та розрахувати" || echo "  ! не нажата кнопка финиша"
sleep 6
echo "  файлы в базе:"
adb shell "sqlite3 /data/data/$PKG/databases/roadscan.db 'select id, endedAtMs, confirmedCount from sessions'" 2>/dev/null \
  || adb shell "ls -la /data/data/$PKG/databases/" 2>/dev/null

say "3. Смена в процессе + убийство процесса"
adb shell input tap 127 2274; sleep 2
tap_exact "Почати зміну" || echo "  ! не нажата кнопка старта"
sleep 12
echo "  смена запущена, убиваем процесс (как это делает Android при нехватке памяти)"
adb shell am kill $PKG 2>/dev/null || adb shell am force-stop $PKG
sleep 3
echo "  база после убийства:"
adb shell "sqlite3 /data/data/$PKG/databases/roadscan.db 'select id, startedAtMs, endedAtMs, confirmedCount, visualOnlyCount from sessions'" 2>/dev/null || echo "  (sqlite3 недоступен — смотрим через приложение)"

say "4. Приложение после перезапуска: смена в списке"
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 6
adb shell input tap 678 2274; sleep 3          # Звіт
dump_ui
python3 - <<'PY'
import re
d = open('/tmp/ui.xml', encoding='utf-8').read()
for m in re.finditer(r'text="([^"]+)"', d):
    t = m.group(1)
    if t.strip() and ('SES-' in t or 'процесі' in t or 'Збережені' in t):
        print('   ', t)
PY

say "5. Возвращаем сеть"
adb shell settings put global airplane_mode_on 0 >/dev/null 2>&1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1
adb shell svc wifi enable >/dev/null 2>&1
adb shell svc data enable >/dev/null 2>&1
echo "  сеть включена"
