# RoadScan AI Agent

Android-приложение с автономным AI-агентом-оркестратором для мобильного комплекса
анализа дорожного покрытия. Реализует ТЗ «RoadScan AI Agent»: Perceive → Reason → Act,
машину состояний, сенсорную фузию, адаптивный FPS, кластеризацию дефектов, расчёт
сметы и офлайн-экспорт PDF / Excel / GeoJSON.

Проект собирается и проходит тесты на JDK 17 + Android SDK 36:

```bash
./gradlew :core:test          # 59 юнит-тестов ядра
./gradlew :app:assembleDebug  # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:demo          # синтетическая смена → XLSX + GeoJSON в core/build/demo
```

## Что внутри

| Модуль | Назначение |
|---|---|
| `:core` | Чистая Kotlin/JVM-логика агента: фузия, severity, геометрия, кластеризация, смета, XLSX/GeoJSON. Без Android-зависимостей, полностью покрыта тестами. |
| `:app` | Android: CameraX, акселерометр 200 Гц, GNSS, TFLite YOLOv8, Room, Compose UI, PDF, шаринг. |

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
│ Room: сессии и дефекты; PostProcessingAgent: кластеры + смета      │
│ ExportManager: PDF-ведомость, Excel-смета, GeoJSON                 │
└────────────────────────────────────────────────────────────────────┘
```

## Быстрый старт на телефоне

1. Соберите `app-debug.apk` и установите: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Выдайте разрешения CAMERA и ACCESS_FINE_LOCATION.
3. Нажмите «Начать смену» — агент проверит GPS, диск и температуру.
4. После поездки — «Завершить и рассчитать», затем PDF / Excel / GeoJSON.

### Модель YOLOv8

Модель в репозиторий не входит (вес ~6–12 МБ). Положите TFLite-экспорт в
`app/src/main/assets/roadscan_yolov8.tflite`:

```bash
yolo export model=roadscan.pt format=tflite imgsz=640 int8=False
cp roadscan_saved_model/roadscan_float32.tflite app/src/main/assets/roadscan_yolov8.tflite
```

Ожидаемые классы (`app/src/main/assets/labels.txt`): `pothole`, `crack_network`,
`rutting`, `patch`, `manhole_cover`. Без модели приложение работает в режиме
телеметрии: камера, GPS, акселерометр и UI живы, детекций нет.

### Калибровка геометрии

Точность площади 10–15% достигается после калибровки под конкретный автомобиль:

* `AgentConfig.cameraHeightM` — высота камеры над дорогой (в `FrameAnalyzer`, по умолчанию 1.3 м);
* `cameraPitchDeg` — наклон вниз (по умолчанию 8°);
* `cameraFocalLengthPx` — фокусное в пикселях (`null` → 0.9 × ширины кадра).

Проверьте на участке с известной ямой: замерьте рулеткой/лазером и подберите
`cameraFocalLengthPx`. Глубина оценивается по удару подвески (`depthBaseMm`,
`depthMmPerG`) и калибруется на контрольных неровностях.

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
| 3.2 Батарея < 20% | `AgentConfig`, `AdaptiveFrameGovernor`, голосовое событие | `low battery…` |
| 3.3 Кластеризация | `DefectClusterer` (DBSCAN на ENU, выпуклая оболочка) | `DefectClustererTest` |
| 3.3 Смета V и M | `EstimateCalculator` | `EstimateCalculatorTest` |
| 3.3 PDF / Excel / GeoJSON | `PdfReportGenerator`, `ReportXlsxBuilder`, `GeoJsonExporter` | `ReportExportTest` |
| 4. Машина состояний | `AgentState`, `RoadScanAgent` | `RoadScanAgentTest` |
| 5. Задержка < 45 мс | Ядро без I/O; `FrameAnalyzer` меряет latency | `stats.averageLatencyMs` |
| 5. RAM / энергия | Буферы ограничены, Bitmap recycle, throttle | — (профилирование на устройстве) |
| 6. Офлайн-first | Room + локальные файлы, нет сетевых вызовов | — |

Полный протокол приёмки — `docs/ACCEPTANCE.md`.

## Метрики и формулы

* Площадь: ground-plane модель `d = H / tan(pitch + atan((v−cy)/f))`,
  `area = w_px·mpp · h_px·mpp / sin(угол визирования)`.
* Объём фрезеровки: `V = S · (H + 30 мм) / 1000`, м³.
* Тоннаж: `M = V · ρ · K_упл`, где `ρ = 2.4 т/м³`, `K_упл = 1.25`.
* Порог удара: `0.28 g + 0.004 g/(км/ч)`, максимум `0.75 g`.
* Дедупликация: 4 м / 6 с, апгрейд visual-only → confirmed при появлении удара.

## Ограничения

* Без обученной модели YOLOv8 детекций нет — это осознанно: репозиторий не тащит веса.
* Монокулярная площадь зависит от калибровки; на невыровненной камере ошибка растёт.
* Глубина по удару — калибруемая эвристика, не замена лазерному профилометру.
* RTK: `LocationCollector.injectRtk(...)` — точка входа для внешнего приёмника по BLE/UART.
* Phrase «голосовые предупреждения» реализованы как события `AgentEvent.VoicePrompt`;
  TTS подключается в UI одним вызовом `TextToSpeech.speak`.

## Структура

```
core/src/main/kotlin/ai/roadscan/core/
  agent/        RoadScanAgent, PostProcessingAgent, AgentConfig
  fusion/       SensorFusionEngine, SeverityScorer
  perception/   AdaptiveFrameGovernor, LensQualityMonitor
  geo/          GeoMath, MonocularAreaEstimator, DefectClusterer
  estimate/     PriceCatalog, EstimateCalculator
  report/       XlsxWriter, ReportXlsxBuilder, GeoJsonExporter
app/src/main/kotlin/ai/roadscan/app/
  perception/   YoloV8Detector, FrameAnalyzer, SensorCollector
  location/     LocationCollector
  telemetry/    TelemetryReader
  data/         Room (сессии, дефекты), репозиторий
  report/       PdfReportGenerator, ExportManager
  ui/           Compose: экран сканирования и отчёт
```

## Тесты

59 тестов: фузия и окно ±50 мс, гистерезис троттлинга, линза, severity, геометрия,
DBSCAN/полигоны, формулы сметы, GeoJSON, валидность XLSX (парсинг всех XML-частей),
производительность кластеризации 3000 точек и сметы на 10 000 кластеров (< 3 с).

```bash
./gradlew :core:test --rerun-tasks
```
