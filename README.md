# RoadScan AI Agent

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
./gradlew :core:test          # 88 юнит-тестов ядра
./gradlew :app:assembleDebug  # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:demo          # синтетическая смена → XLSX + GeoJSON в core/build/demo
./tools/license.py issue --org "КП Шляхове господарство" --tier PRO --days 365
```

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
│ SensorFusionEngine: пик |Δa_z| в окне ±50 мс                       │
│ MonocularAreaEstimator + DepthEstimator: площадь и глубина         │
│ SeverityScorer 1–5, дедупликация 4 м / 6 с                         │
│ LensQualityMonitor: 10 «грязных» кадров подряд                     │
└────────────────────────────────┬───────────────────────────────────┘
                                 ▼
┌──────────────────────── Action & Management ───────────────────────┐
│ RoadScanAgent: IDLE→CALIBRATING→SCANNING↔THROTTLED→POST_PROCESSING │
│ AdaptiveFrameGovernor: 30→15→6 FPS по нагреву/батарее              │
│ Room: сессии/дефекты/реквизиты; PostProcessingAgent: кластеры+смета│
│ ExportManager: PDF-ведомость, Excel-смета, GeoJSON                 │
└────────────────────────────────────────────────────────────────────┘
```

### Экраны

* **Сканирование** — камера, телеметрия (FPS, кадры, дефекты, батарея, SoC, GPS,
  диск), голосовые подсказки, старт/стоп смены.
* **Отчёт** — KPI (площадь/фрезеровка/асфальт/смета), строки сметы, кластеры,
  метрики, экспорт PDF/Excel/GeoJSON, список сохранённых смен.
* **Настройки** — лицензия (активация/статус), калибровка камеры с калькулятором
  фокуса, реквизиты документов (объект, дорога, заказчик, подрядчик, договор,
  оператор, ТС, погода).

## Быстрый старт на телефоне

1. Установите APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Выдайте разрешения CAMERA и ACCESS_FINE_LOCATION.
3. Заполните «Настройки» → калибровка и реквизиты (один раз на автомобиль/объект).
4. «Начать смену» — агент проверит GPS, диск и температуру.
5. После поездки — «Завершить и рассчитать», затем PDF / Excel / GeoJSON.

## Модель YOLO

В `app/src/main/assets/roadscan_yolov8.tflite` лежит YOLO11n, обученная на
RDD2022 (лицензия Apache-2.0), вход 640×640 float32, выход `[1, 10, 8400]`
(4 координаты + 6 классов). Экспорт выполнен через ONNX → onnx2tf, потому что
`litert-torch` требует Python ≥3.10.

Классы в `assets/labels.txt` (порядок важен):

```
longitudinal
equal interval
alligator
pothole
crosswalk blur
whiteline blur
```

Маппинг: `pothole → Яма`, `alligator/longitudinal/equal interval → Сеть трещин`,
`crosswalk blur` и `whiteline blur` — служебные классы разметки, отбрасываются.
Модель можно заменить своим экспортом с тем же форматом выхода и тем же
порядком классов.

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
| 3.3 Кластеризация | `DefectClusterer` (DBSCAN на ENU, выпуклая оболочка) | `DefectClustererTest` |
| 3.3 Смета V и M | `EstimateCalculator` | `EstimateCalculatorTest` |
| 3.3 PDF / Excel / GeoJSON | `PdfReportGenerator`, `ReportXlsxBuilder`, `GeoJsonExporter` | `ReportExportTest` |
| 3.3 Реквизиты в документах | `ReportMeta` | `work order details…` |
| 4. Машина состояний | `AgentState`, `RoadScanAgent` | `RoadScanAgentTest` |
| 5. Задержка < 45 мс | Ядро без I/O; `FrameAnalyzer` меряет latency | `stats.averageLatencyMs` |
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
* Порог удара: `0.28 g + 0.004 g/(км/ч)`, максимум `0.75 g`.
* Дедупликация: 4 м / 6 с, апгрейд visual-only → confirmed при появлении удара.

## Ограничения

* Модель обучена на RDD2022 (Япония/Индия/Чехия); для украинских объектов нужна
  дообучение/валидация на локальном датасете (ями с водой, заплатки, зимова хімія).
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

88 тестов: фузия и окно ±50 мс, гистерезис троттлинга, линза, severity, геометрия,
DBSCAN/полигоны, формулы сметы, GeoJSON, валидность XLSX (парсинг всех XML-частей),
производительность кластеризации 3000 точек и сметы на 10 000 кластеров (< 3 с),
проверка/истечение/подделка лицензий, калибровка и фокус, планувальник
відеомонтажу (highlights/full, вікна подій, ліміти тривалості) і демо-сесія.

```bash
./gradlew :core:test --rerun-tasks
```
