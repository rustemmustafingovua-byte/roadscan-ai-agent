# RoadScan AI Agent

[![Android CI](https://github.com/rustemmustafingovua-byte/roadscan-ai-agent/actions/workflows/android.yml/badge.svg)](https://github.com/rustemmustafingovua-byte/roadscan-ai-agent/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

> **Українською коротко.** Android-застосунок для обстеження дорожнього покриття:
> камера + акселерометр + GNSS у реальному часі знаходять дефекти, а після зміни
> самі формують **кошторис (Excel)**, **відомість (PDF)** і **GeoJSON** для ГІС —
> у гривні з ПДВ 20 %, за термінологією ДСТУ, повністю офлайн. Окремо є
> **Відеостудія**: сире відео → нейромережа сама знаходить дефекти → готовий
> ролик із титрами та рамками `ID · Впевненість`, без звуку.
> Деталі: [`docs/UKRAINE.md`](docs/UKRAINE.md), комерційна модель —
> [`docs/COMMERCIAL.md`](docs/COMMERCIAL.md).

Android-приложение с автономным AI-агентом-оркестратором для мобильного комплекса
анализа дорожного покрытия. Реализует ТЗ «RoadScan AI Agent»: Perceive → Reason → Act,
машину состояний, сенсорную фузию, адаптивный FPS, кластеризацию дефектов, расчёт
сметы и офлайн-экспорт PDF / Excel / GeoJSON.

Продукт подготовлен к коммерческой эксплуатации дорожными службами: есть
калибровка под автомобиль, реквизиты объекта/договора в документах, офлайн-лицензия
с пробным периодом и водяным знаком. Коммерческая модель, цены, состав поставки и
честный список того, что нужно закрыть до платных внедрений — в
[`docs/COMMERCIAL.md`](docs/COMMERCIAL.md).

```bash
./gradlew :core:test          # 101 юнит-тест ядра
./gradlew :app:assembleDebug  # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease # подписанный APK (нужен keystore.properties)
./gradlew :core:demo          # синтетическая смена → XLSX + GeoJSON в core/build/demo
./tools/license.py issue --org "КП Шляхове господарство" --tier PRO --days 365
./tools/qa-device.sh          # сквозной прогон на подключённом устройстве
```

## Релизная сборка

Ключ подписи лежит вне репозитория (`keystore.properties` + `*.jks` в
`.gitignore`). Один раз:

```bash
keytool -genkeypair -v -keystore secrets/roadscan-release.jks -alias roadscan \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=RoadScan AI, O=RoadScan, C=UA"
cat > keystore.properties <<'EOF'
storeFile=../secrets/roadscan-release.jks
storePassword=…
keyAlias=roadscan
keyPassword=…
EOF
./gradlew :app:assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk` (подпись v2+v3).
Установка: `adb install -r app/build/outputs/apk/release/app-release.apk`.

Перед выездом в «Налаштуваннях» есть **проверка готовности**: модель, камера,
датчик ускорения, GNSS, место, заряд, лицензия — одним нажатием и офлайн.

## Что внутри

| Модуль | Назначение |
|---|---|
| `:core` | Чистая Kotlin/JVM-логика: фузия, severity, геометрия, кластеризация, смета, XLSX/GeoJSON, калибровка, лицензии. Без Android-зависимостей, покрыта тестами. |
| `:app` | Android: CameraX, акселерометр 200 Гц, GNSS, TFLite YOLO, Room, Compose UI, PDF, TTS, шаринг. |

### Три контура агента

```
┌──────────────────────────── Perception ────────────────────────────┐
│ CameraX 1280×720 ──► FrameAnalyzer ──► YoloV8Detector (TFLite)     │
│ SensorManager 200 Гц ──► SensorCollector ──► SensorFusionEngine    │
│ LocationManager GPS/RTK ──► LocationCollector                      │
│ Battery/Thermal/StatFs ──► TelemetryReader                         │
└────────────────────────────────┬───────────────────────────────────┘
                                 ▼
┌──────────────────────── Cognitive & Fusion ────────────────────────┐
│ SensorFusionEngine: пик |Δa| в окне ±50 мс (модуль ускорения, g)   │
│ MonocularAreaEstimator + DepthEstimator: площадь и глубина         │
│ SeverityScorer 1–5, дедупликация 4 м / 6 с                         │
│ LensQualityMonitor: 10 «грязных» кадров подряд                     │
└────────────────────────────────┬───────────────────────────────────┘
                                 ▼
┌──────────────────────── Action & Management ───────────────────────┐
│ RoadScanAgent: IDLE→CALIBRATING→SCANNING↔THROTTLED→POST_PROCESSING │
│ AdaptiveFrameGovernor: 30→15→6 FPS по нагреву/батарее              │
│ Фоновый режим: vision выключен, пишется только путевой лог         │
│ Room: сессии/дефекты/реквизиты; PostProcessingAgent: кластеры+смета│
│ ExportManager: PDF-ведомость, Excel-смета, GeoJSON                 │
└────────────────────────────────────────────────────────────────────┘
```

### Экраны

* **Сканирование** — консоль из трёх панелей: слева состояние (покрытие,
  счётчики дефектов, сводка по типам со средней уверенностью, график качества
  модели, система, маршрут), в центре — кадр с подписанными находками
  (`ЯМА · ID: P-003, Conf: 82%`), справа — инспектор дефекта с валидацией
  оператором (**Очікує / Підтверджено / Хибне**). Отклонённая находка не
  попадает ни в ведомость, ни в смету; подтверждённая оператором — попадает,
  даже если автоматика удар не увидела.
  На планшете и в альбомной ориентации — все три панели; на телефоне —
  камера, строка показателей и выдвижная шторка с находками.
* **Отчёт** — KPI (площадь/фрезеровка/асфальт/смета), строки сметы, кластеры,
  метрики, экспорт PDF/Excel/GeoJSON/**комплект** (ZIP с манифестом), список
  сохранённых смен. В PDF-ведомость и в комплект входят **снимки находок**:
  подрядчик видит яму, а не только строку в таблице.
* **Видео** — два режима: ролик-обзор с разметкой и **обследование участка**
  по готовым роликам из галереи (дефекты → ведомость → смета, к каждой
  находке снимается кадр из ролика).
* **Настройки** — лицензия (активация/статус), калибровка камеры с калькулятором
  фокуса, реквизиты документов (объект, дорога, заказчик, подрядчик, договор,
  оператор, ТС, погода, участок в км), проверка готовности комплекта.

## Быстрый старт на телефоне

1. Установите APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Выдайте разрешения CAMERA и ACCESS_FINE_LOCATION.
3. Заполните «Настройки» → калибровка и реквизиты (один раз на автомобиль/объект).
4. «Начать смену» — агент проверит GPS, диск и температуру.
5. После поездки — «Завершить и рассчитать», затем PDF / Excel / GeoJSON.

## Модель YOLO

В `app/src/main/assets/roadscan_yolov8.tflite` лежит **YOLOv8m** («Road Anomaly»),
обученный на открытых наборах дефектов покрытия и дообученный авторами модели:
вход 512×512 float32 (размер определяется из тензора модели — менять код не нужно),
выход `[1, 11, 5376]` (4 координаты + 7 классов). Поставляется в
dynamic-range int8: веса int8, вход и выход float32 — 26 МБ вместо 103 МБ
без потери качества.

Классы в `assets/labels.txt` (порядок важен):

```
Heavy-Vehicle
Light-Vehicle
Pedestrian
Crack
Crack-Severe
Pothole
Speed-Bump
```

Маппинг: `Pothole → Яма`, `Crack` и `Crack-Severe → Сеть трещин`;
`Heavy-Vehicle`, `Light-Vehicle`, `Pedestrian`, `Speed-Bump` — не дефекты
покрытия, они отбрасываются. Модель можно заменить своим экспортом с тем же
форматом выхода и тем же порядком классов.

**Почему эта модель.** Прежняя (YOLOv8n на RDD2022, Япония/Индия/Чехия) на
реальных украинских кадрах **не находила крупные ямы вообще** — на контрольном
наборе полнота была 0 %. Новая модель на том же наборе даёт 100 % полноты и
0 ложных срабатываний на чистом асфальте. Цифры и методика замера —
в [`docs/THIRD_PARTY.md`](docs/THIRD_PARTY.md), инструмент — `tools/eval_models.py`.

> Для официальных смет нужна модель, дообученная на локальном размеченном
> датасете заказчика: текущая — универсальная, украинскую специфику
> (заплатки, зимняя химия, люки) она не знает.

**Ускорение инференса.** Интерпретатор поднимается с делегатом NNAPI
(аппаратное ускорение: DSP/GPU/NPU), при неудаче — на XNNPACK. Часть
драйверов NNAPI отдаёт программный fallback, который медленнее XNNPACK,
поэтому после первых кадров агент меряет реальную скорость и при превышении
80 мс возвращается на XNNPACK. Активный бэкенд виден в HUD («Обчислення») —
это первое, что стоит проверить, если задержка не укладывается в бюджет 45 мс.

## Калибровка геометрии

Точность площади 10–15% достигается после калибровки под конкретный автомобиль
(экран «Настройки»):

* `cameraHeightM` — высота оптического центра над дорогой;
* `cameraPitchDeg` — наклон вниз;
* `focalLengthPx` — фокусное в пикселях. Калькулятор: `f = w_px · Z / W`
  (известная ширина объекта, дистанция, ширина в пикселях).

Профиль сохраняется в SharedPreferences, применяется при старте каждой смены и
печатается в шапке Excel/PDF как аудит методики.

## Лицензирование

* Формат ключа: `RSL1.<base64url(payload)>.<base64url(ECDSA P-256)>`.
* Проверка — по встроенному публичному ключу, без сети.
* Пробный период: 14 дней / 5 смен, документы с водяным знаком «ДЕМО».
* Выпуск: `./tools/license.py issue --org "…" --tier PRO --days 365 --out key.txt`.
  Закрытый ключ — `~/.config/opencode/secrets/roadscan-license-key.pem` (вне git).

## Архив, аналитика и приёмка

* **Сравнение «до/после»** — галочками выбираются две смены в отчёте:
  изменение дефектов в %, было/стало по площади и смете, «устранено ·
  осталось · новых» (сопоставление по координатам, радиус 5 м, либо по
  километражу видео-участка без GPS, радиус 30 м; без координат и км —
  честно только агрегаты), разбивка по типам, тренд цветом.
* **PDF-акт приёмки** — кнопка «Акт (PDF)» в диалоге сравнения:
  подписанный документ «было → стало» с росчерком оператора — для
  подписи на приёмке ремонтных работ.
* **Структура дефектов** — столбики долей каждого типа по всем
  завершённым сменам в «Итогах архива» (DAO GROUP BY).
* **Фильтр периода** — чипы «увесь час / за добу / за тиждень /
  за місяць»: сводка, структура дефектов, список и экспорт архива
  считаются на выбранном периоде.
* **Экспорт всего архива** — комплект (PDF + XLSX + GeoJSON + снимки)
  каждой завершённой смены одним ZIP.
* **Удаление смены** — долгое нажатие → подтверждение; удаляются смена,
  дефекты и снимки на диске (идущую смену удалить нельзя).
* **Справка оператора** — пятый таб «Довідка»: быстрый старт, работа
  с видео, приёмка, настройки и советы — офлайн, без сети.
* **Цены под договор** — настройки → «Ціни робіт»: правка расценок
  каталога (сохраняется офлайн, применяется к новым сметам).

## Соответствие ТЗ

| Пункт ТЗ | Реализация | Проверка |
|---|---|---|
| 2. Perception Engine | `FrameAnalyzer`, `SensorCollector`, `LocationCollector`, `TelemetryReader` | `RoadScanAgentTest` |
| 2. Sensor Fusion ±50 мс | `SensorFusionEngine.peakAround` | `SensorFusionEngineTest` |
| 2. Severity 1–5 | `SeverityScorer` | `SeverityScorerTest` |
| 2. Фильтрация ложных | `RoadScanAgent.evaluateFrameAndSensor` (удар + ближнее поле) | `RoadScanAgentTest`, `SeverityScorerTest` |
| 3.1 Старт: GPS/диск/температура | `RoadScanAgent.startSession` | `start requires free disk`, `poor gps…` |
| 3.1 Adaptive FPS 42°C → 15/6 FPS | `AdaptiveFrameGovernor` | `AdaptiveFrameGovernorTest` |
| 3.1 Visual-only | `ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY` | `pothole without impact…` |
| 3.2 Грязная линза, 10 кадров | `LensQualityMonitor` | `LensQualityMonitorTest`, `ten dirty frames…` |
| 3.2 Батарея < 20% | `AgentConfig`, `AdaptiveFrameGovernor`, голосовое событие + TTS | `low battery…` |
| 3.2 Предложение режима при низком заряде | `AgentEvent.BatteryLow` → диалог «Фоновый режим / Сохранить и завершить» | `critical battery is reported separately` |
| 3.2 Фоновый режим (vision off) | `RoadScanAgent.setVisionEnabled`, `FrameAnalyzer.visionEnabledProvider` | `background mode stops vision analysis…` |
| 3.3 Кластеризация | `DefectClusterer` (DBSCAN на ENU, выпуклая оболочка) | `DefectClustererTest` |
| 3.3 Смета V и M | `EstimateCalculator` | `EstimateCalculatorTest` |
| 3.3 PDF / Excel / GeoJSON | `PdfReportGenerator`, `ReportXlsxBuilder`, `GeoJsonExporter` | `ReportExportTest` |
| 3.3 Реквизиты в документах | `ReportMeta` | `work order details…` |
| 4. Машина состояний | `AgentState`, `RoadScanAgent` | `RoadScanAgentTest` |
| 5. Задержка < 45 мс | Ядро без I/O; `FrameAnalyzer` меряет latency; NNAPI → XNNPACK c авто-откатом | `stats.averageLatencyMs` |
| 5. RAM / энергия | Буферы ограничены, Bitmap recycle, throttle | — (профилирование на устройстве) |
| 6. Офлайн-first | Room + локальные файлы, нет сетевых вызовов | — (авиарежим) |

Полный протокол приёмки — [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md),
коммерческий пакет — [`docs/COMMERCIAL.md`](docs/COMMERCIAL.md), локаль и
ДСТУ — [`docs/UKRAINE.md`](docs/UKRAINE.md), release-сборка —
[`docs/RELEASE.md`](docs/RELEASE.md), правові документи —
[`docs/EULA.md`](docs/EULA.md) і [`docs/PRIVACY.md`](docs/PRIVACY.md).

## Метрики и формулы

* Площадь: ground-plane модель `d = H / tan(pitch + atan((v−cy)/f))`,
  `area = w_px·mpp · h_px·mpp / sin(угол визирования)`.
* Объём фрезеровки: `V = S · (H + 30 мм) / 1000`, м³.
* Тоннаж: `M = V · ρ · K_упл`, где `ρ = 2.4 т/м³`, `K_упл = 1.25`.
* Удар подвески: `Δa = | |a| − 1 g |`, окно ±50 мс вокруг момента съёмки кадра.
  Порог: `0.28 g + 0.004 g/(км/ч)`, максимум `0.75 g`.
* Дедупликация: 4 м / 6 с, апгрейд visual-only → confirmed при появлении удара.

### Почему Δa, а не Δa_z

ТЗ формулирует правило через `Δa_z`. Реализация считает отклонение **модуля**
ускорения от 1 g, и это не косметика:

| | Δa_z | Δa (модуль) |
|---|---|---|
| Телефон «плашмя», ось Z вверх | работает | работает |
| Телефон на лобовом стекле, ось Z горизонтальна | «покой» ≈ 1.0 g → ложный удар на каждом кадре | покой = 1 g → ложных срабатываний нет |
| Телефон перевёрнут | покой = −1 g → ложный удар | покой = 1 g → ложных срабатываний нет |
| Торможение 0.4 g поперёк тяжести | может выглядеть как удар | ~0.08 g — ниже порога |

Модуль инвариантен к ориентации крепления и сам по себе отсекает поперечные
перегрузки. Отсчёты Android-акселерометра приходят в м/с² и нормализуются на
границе (`SensorSample.fromMetersPerSecondSquared`) — без этого «покой» даёт
9.8 вместо 1.0 и подтверждается любой визуальный кандидат.

## Ограничения

* Модель обучена на открытых наборах дефектов покрытия и дообучена авторами
  модели ([`docs/THIRD_PARTY.md`](docs/THIRD_PARTY.md)); для украинских объектов
  всё равно нужна дообучка на локальном датасете заказчика (ями с водой,
  заплатки, зимова хімія).
* Монокулярная площадь зависит от калибровки; на невыровненной камере ошибка растёт.
* Глубина по удару — калибруемая эвристика, не замена лазерному профилометру.
* RTK: `LocationCollector.injectRtk(...)` — точка входа для внешнего приёмника по BLE/UART.
* Для официальных сметных документов нужна аттестованная методика/поверка СИ —
  см. `docs/COMMERCIAL.md`, раздел 6.
* Полевые метрики (85% ложных, latency, энергопотребление) проверяются на
  устройстве по протоколу приёмки.

## Структура

```
core/src/main/kotlin/ai/roadscan/core/
  agent/        RoadScanAgent, PostProcessingAgent, AgentConfig
  calibration/  CalibrationProfile (геометрия камеры, фокус)
  fusion/       SensorFusionEngine, SeverityScorer
  perception/   AdaptiveFrameGovernor, LensQualityMonitor
  geo/          GeoMath, MonocularAreaEstimator, DefectClusterer
  estimate/     PriceCatalog, EstimateCalculator
  report/       XlsxWriter, ReportXlsxBuilder, GeoJsonExporter, ReportMeta
  license/      LicenseVerifier, LicenseState, Edition
  i18n/         DocStrings, CoreMessages (uk за замовчуванням, ru)
  video/        Montage (планувальник highlights/full)
  demo/         DemoSession (еталонна ділянка для показу)
app/src/main/kotlin/ai/roadscan/app/
  perception/   YoloV8Detector, FrameAnalyzer, SensorCollector
  location/     LocationCollector
  telemetry/    TelemetryReader
  data/         Room (сессии, дефекты, реквизиты), репозиторий
  settings/     SettingsStore (калибровка, реквизиты, лицензия)
  report/       PdfReportGenerator, ExportManager
  video/        VideoStreamDecoder, VideoAnalyzer, VideoBrander, Mp4Encoder
  ui/           Compose: сканирование, Відеостудія, отчёт, настройки
tools/          license.py — выпуск и проверка офлайн-лицензий
docs/           ACCEPTANCE, COMMERCIAL, UKRAINE, RELEASE, EULA, PRIVACY
```

## Тесты

101 тест: фузия и окно ±50 мс, единицы акселерометра (м/с² → g) и
инвариантность к ориентации крепления, гистерезис троттлинга, линза, severity,
геометрия, DBSCAN/полигоны, формулы сметы, GeoJSON (все объекты — полигоны,
кольца по RFC 7946), валидность XLSX (парсинг всех XML-частей),
производительность кластеризации 3000 точек и сметы на 10 000 кластеров
(< 3 с), проверка/истечение/подделка лицензий, калибровка и фокус,
планувальник відеомонтажу (highlights/full, вікна подій, ліміти тривалості),
фоновый режим и двухуровневое предупреждение о заряде, демо-сесія.

```bash
./gradlew :core:test --rerun-tasks
```
