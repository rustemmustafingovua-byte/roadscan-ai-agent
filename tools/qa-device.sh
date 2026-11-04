#!/bin/bash
# Сквозной QA-прогон RoadScan на устройстве: все экраны и действия,
# с перехватом ошибок/предупреждений приложения из logcat.
set -u
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
PKG=ai.roadscan.app
OUT=${OUT:-/tmp/rs-qa}
mkdir -p $OUT
LOG=$OUT/logcat.txt
: > $LOG

say() { echo; echo "=== $* ==="; }

pid() { adb shell pidof $PKG 2>/dev/null | tr -d '\r'; }

capture() {
  local p; p=$(pid)
  if [ -n "$p" ]; then
    adb logcat -d -v threadtime 2>/dev/null | awk -v p="$p" '
      $3 ~ "^"p"$" || $4 ~ "^"p"$" || $0 ~ /'"$PKG"'/ { print }
    ' >> $LOG
  fi
}

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
}

# tap <точный текст> — тапает элемент с точным совпадением text
tap() {
  local label="$1"
  dump_ui
  local coord
  coord=$(python3 - "$label" <<'PY'
import sys, re
target = sys.argv[1]
try:
    d = open('/tmp/ui.xml', encoding='utf-8').read()
except Exception:
    print(''); raise SystemExit
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', d):
    if m.group(1) == target:
        x1,y1,x2,y2 = map(int, m.groups()[1:])
        print((x1+x2)//2, (y1+y2)//2); break
else:
    print('')
PY
)
  if [ -z "$coord" ]; then echo "  ! не найдено: '$label'"; return 1; fi
  local x y
  x=$(echo "$coord" | cut -d' ' -f1)
  y=$(echo "$coord" | cut -d' ' -f2)
  echo "  tap '$label' -> $x,$y"
  adb shell input tap "$x" "$y"
  sleep 2
}

scroll_down() { adb shell input swipe 540 1700 540 700 250; sleep 1; }

# Прокрутка до элемента: пока не найдём — листаем вниз.
scroll_to() {
  local label="$1" i
  for i in 1 2 3 4 5 6; do
    dump_ui
    if grep -q "text=\"$label\"" /tmp/ui.xml 2>/dev/null; then return 0; fi
    scroll_down
  done
  return 1
}

# Файлы приложения: run-as не работает для release-сборки, поэтому adb root.
app_files() {
  adb root >/dev/null 2>&1
  sleep 1
  adb shell find /data/data/ai.roadscan.app/cache/exports -type f 2>/dev/null
}

say "1. Чистый старт"
adb logcat -c
adb shell pm clear $PKG >/dev/null
adb shell pm grant $PKG android.permission.CAMERA
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 6
echo "  PID: $(pid)"

say "2. Активация лицензии (deep link + сериализация)"
KEY=$(cat /tmp/rs-key.txt 2>/dev/null | tr -d '\r\n')
adb shell am start -a android.intent.action.VIEW -d "roadscan://license?key=$KEY" $PKG >/dev/null 2>&1
sleep 3
adb shell input tap 950 2274   # Налаштування
sleep 2
dump_ui
python3 - <<'PY'
import re
try: d=open('/tmp/ui.xml',encoding='utf-8').read()
except Exception: d=''
for m in re.finditer(r'text="([^"]*)"', d):
    t=m.group(1)
    if t and ('PRO' in t.upper() or 'КП ' in t or 'Пілот' in t or 'активна' in t
              or 'дійсн' in t or 'нтервал' in t or 'Pro' in t):
        print('   ', t)
PY

say "3. Старт смены"
adb shell input tap 127 2274   # Сканирование
sleep 2
tap "Почати зміну" || true
sleep 12
dump_ui
python3 - <<'PY'
import re
d=open('/tmp/ui.xml',encoding='utf-8').read()
want=['кадрів','підтв','візуальних','FPS','Затримка','Обчислення','Батарея','GPS']
for m in re.finditer(r'text="([^"]+)"', d):
    t=m.group(1)
    if any(w in t for w in want): print('   ', t)
PY

say "4. Низкий заряд -> диалог -> фоновый режим"
adb shell dumpsys battery set level 15 >/dev/null
sleep 6
dump_ui
python3 - <<'PY'
import re
d=open('/tmp/ui.xml',encoding='utf-8').read()
for m in re.finditer(r'text="([^"]+)"', d):
    t=m.group(1)
    if any(w in t for w in ['Фоновий','Зберегти','Продовжити','Низький','заряд']): print('   ', t)
PY
tap "Фоновий режим" || true
sleep 5
dump_ui
python3 - <<'PY'
import re
d=open('/tmp/ui.xml',encoding='utf-8').read()
for m in re.finditer(r'text="([^"]+)"', d):
    t=m.group(1)
    if 'ФОНОВИЙ' in t.upper(): print('    HUD:', t)
PY

say "5. Завершение смены и расчет"
adb shell dumpsys battery set level 80 >/dev/null
tap "Завершити та розрахувати" || true
sleep 6

say "6. Демо-отчет + экспорт PDF/XLSX/GeoJSON"
adb shell input tap 950 2274; sleep 2
scroll_to "Сформувати демо-звіт" || echo "  ! кнопка демо не найдена"
tap "Сформувати демо-звіт" || true
sleep 5
adb shell input tap 678 2274; sleep 3   # Звіт
tap "PDF" || true; sleep 4
adb shell input keyevent KEYCODE_BACK; sleep 1
tap "Excel" || true; sleep 4
adb shell input keyevent KEYCODE_BACK; sleep 1
tap "GeoJSON" || true; sleep 4
adb shell input keyevent KEYCODE_BACK; sleep 1
echo "  файлы:"
app_files | sed 's/^/    /'

say "7. Видеостудия"
adb shell input tap 403 2274; sleep 2   # Відео
dump_ui
python3 - <<'PY'
import re
d=open('/tmp/ui.xml',encoding='utf-8').read()
for m in re.finditer(r'text="([^"]+)"', d):
    t=m.group(1)
    if t and len(t)<40: print('   ', t)
PY

say "8. Итог: ошибки и предупреждения приложения"
capture
echo "  строк в логе: $(wc -l < $LOG)"
echo "  --- ERROR/FATAL ---"
grep -E "^.*[EW]/" $LOG | grep -vE "OpenGLRenderer|BLASTBufferQueue|Choreographer|libEGL|Codec2Client|MediaCodec|CameraX|Camera2CameraImpl|BufferQueue" | head -40
echo "  --- исключения ---"
grep -iE "exception|error|failed|cannot|unable" $LOG | grep -viE "tflite.*sample|NNAPI SL driver|allowlist" | head -30

echo
echo "Лог: $LOG"
