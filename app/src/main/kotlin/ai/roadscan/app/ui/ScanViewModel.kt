package ai.roadscan.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.Intent
import java.io.File
import android.net.Uri
import androidx.core.content.FileProvider
import android.speech.tts.TextToSpeech
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import ai.roadscan.app.R
import ai.roadscan.app.RoadScanApp
import ai.roadscan.app.data.SessionEntity
import ai.roadscan.app.location.LocationCollector
import ai.roadscan.app.perception.FrameAnalyzer
import ai.roadscan.app.perception.OverlayDetection
import ai.roadscan.app.perception.SensorCollector
import ai.roadscan.app.perception.RoadMaskSegmenter
import ai.roadscan.app.perception.YoloV8Detector
import ai.roadscan.app.report.ExportManager
import ai.roadscan.app.report.ReportFormat
import ai.roadscan.app.telemetry.TelemetryReader
import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.agent.PostProcessingAgent
import ai.roadscan.core.agent.RoadScanAgent
import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.agent.SessionStats
import ai.roadscan.core.calibration.CalibrationProfile
import ai.roadscan.core.demo.DemoSession
import ai.roadscan.core.i18n.CoreMessages
import ai.roadscan.core.i18n.DocStrings
import ai.roadscan.core.license.LicenseState
import ai.roadscan.core.model.AgentEvent
import ai.roadscan.core.model.AgentState
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.EnvironmentSnapshot
import ai.roadscan.core.model.GeoPoint
import ai.roadscan.core.model.ReviewDecision
import ai.roadscan.core.readiness.Readiness
import ai.roadscan.core.readiness.ReadinessEvaluator
import ai.roadscan.core.report.ReportMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Сводка по одному типу дефектов: количество и средняя уверенность. */
data class DefectSummary(
    val type: DefectType,
    val count: Int,
    val avgConfidence: Float,
)

data class UiState(
    val running: Boolean = false,
    val agentState: AgentState = AgentState.IDLE,
    val targetFps: Int = 30,
    val frames: Int = 0,
    val confirmed: Int = 0,
    val visualOnly: Int = 0,
    val speedKmh: Float = 0f,
    val batteryPercent: Int = 100,
    val cpuTempC: Float = Float.NaN,
    val gpsAccuracyM: Float = Float.NaN,
    val freeDiskMb: Long = 0,
    val events: List<String> = emptyList(),
    val report: SessionReport? = null,
    val sessions: List<SessionEntity> = emptyList(),
    /** Границы периода архива включительно; null — весь архив. */
    val periodFromMs: Long? = null,
    val periodToMs: Long? = null,
    /** Выбранный пресет периода (часы); null — весь архив. */
    val periodHoursBack: Long? = null,
    /** Структура дефектов архива: тип → сколько (по завершённым сменам). */
    val archiveTypes: List<Pair<ai.roadscan.core.model.DefectType, Int>> = emptyList(),
    /** Выбранные для сравнения «до/после» смены (максимум две). */
    val compareIds: List<String> = emptyList(),
    /** Результат сравнения двух смен; null — диалог закрыт. */
    val delta: ai.roadscan.core.report.SessionDelta? = null,
    /** Отчёты двух смен для экспорта акта приёмки («до» и «после»). */
    val compareReports: Pair<SessionReport, SessionReport>? = null,
    val comparing: Boolean = false,
    val modelReady: Boolean = false,
    /** Активный бэкенд инференса: NNAPI или XNNPACK. */
    val inferenceBackend: String = "XNNPACK",
    val busy: Boolean = false,
    val message: String? = null,
    val calibration: CalibrationProfile = CalibrationProfile.DEFAULT,
    val meta: ReportMeta = ReportMeta.EMPTY,
    val license: LicenseState = LicenseState(),
    val licenseSummary: String = "",
    val canExport: Boolean = true,
    // --- HUD ---
    /** Рамки дефектов текущего кадра с устойчивыми подписями. */
    val overlay: List<OverlayDetection> = emptyList(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameContrast: Float = 0f,
    val latencyMs: Double = 0.0,
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN,
    val street: String = "",
    val city: String = "",
    /** Трек смены для мини-карты (последние ~2 км). */
    val track: List<GeoPoint> = emptyList(),
    /** Сводка по типам дефектов для панели статистики. */
    val summary: List<DefectSummary> = emptyList(),
    /** Действующий каталог цен (с правками оператора). */
    val priceCatalog: ai.roadscan.core.estimate.PriceCatalog = ai.roadscan.core.estimate.PriceCatalog.DEFAULT,
    val currencySymbol: String = "₴",
    /**
     * Агент предлагает выбрать режим при низком заряде (ТЗ п. 3.2):
     * фоновый режим с минимальным энергопотреблением либо завершение смены.
     */
    val batteryOffer: Boolean = false,
    /** Заряд, на котором предложение было выдвинуто, % — для текста диалога. */
    val batteryOfferPercent: Int = 0,
    /** Фоновый режим включён: vision выключен, пишется только путевой лог. */
    val backgroundMode: Boolean = false,
    /** Результат проверки готовности комплекта. */
    val diagnostics: List<ai.roadscan.app.diagnostics.DiagnosticCheck> = emptyList(),
    /** Дефекты текущей смены — для инспектора и валидации оператором. */
    val defects: List<ai.roadscan.core.model.DefectRecord> = emptyList(),
    /** Выбранный дефект (для правой панели). */
    val selectedDefectId: String? = null,
    /**
     * Уверенность детектора по последним кадрам — график качества модели.
     */
    val performance: List<Float> = emptyList(),
    /** Готовность к смене: что ещё нужно сделать оператору. */
    val readiness: Readiness = ReadinessEvaluator.evaluate(
        permissionsGranted = true,
        licenseActive = true,
        calibrationReady = true,
        documentsFilled = true,
        equipmentReady = true,
    ),
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as RoadScanApp).container
    private val settings = container.settings

    private val language: String = Locale.getDefault().language
    private val messages: CoreMessages = CoreMessages.of(language)
    private val docStrings: DocStrings = DocStrings.of(language)

    private var calibration: CalibrationProfile = settings.calibration()
    private var meta: ReportMeta = settings.reportMeta()
    private var license: LicenseState = settings.licenseState()

    private val roadMask = RoadMaskSegmenter(application)
    private val detector = YoloV8Detector(application, roadMask = roadMask)

    /** Агент пересобирается при изменении калибровки — без перезапуска приложения. */
    private var agent: RoadScanAgent = buildAgent(calibration)
    private var postProcessing = PostProcessingAgent(agent.config, container.priceCatalog)

    private val repository = container.repository
    private val exports = ExportManager(application, docStrings)

    private val location = LocationCollector(application)
    private val photoStore = ai.roadscan.app.perception.DefectPhotoStore(application)
    private val telemetry = TelemetryReader(application, location)
    private val sensors = SensorCollector(application, { agent })

    private var lastOverlayUpdateMs = 0L

    /** Сколько точек держим в графике качества модели. */
    private val PERFORMANCE_HISTORY = 60

    /** Идентификаторы дефектов, уже записанных в базу текущей смены. */
    private val persistedDefectIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    val analyzer = FrameAnalyzer(
        agentProvider = { agent },
        calibrationProvider = { calibration },
        locationProvider = { location.latest },
        speedProvider = { location.speedKmh },
        detectionsProvider = { detector.lastDetections },
        visionEnabledProvider = { agent.visionEnabled },
        detectorLatencyMsProvider = { detector.lastLatencyMs },
        backendProvider = { detector.backend.name },
        onDefectPhoto = { defectId, bitmap ->
            // Снимок находки: в ведомость и в комплект документов.
            val path = photoStore.save(defectId, bitmap)
            if (path != null) {
                agent.setPhoto(defectId, path)
                viewModelScope.launch { runCatching { repository.setPhoto(defectId, path) } }
            }
        },
        onFrame = { metrics ->
            val now = System.currentTimeMillis()
            // 30 обновлений/с перерисовывать HUD незачем: 8–10 достаточно.
            if (now - lastOverlayUpdateMs >= 100) {
                lastOverlayUpdateMs = now
                // График качества: уверенность лучшей находки в кадре.
                // По нему видно, что модель «поплыла» (мокрый асфальт, ночь).
                val bestConfidence = metrics.overlays.maxOfOrNull { it.confidence } ?: 0f
                _state.update {
                    it.copy(
                        performance = (it.performance + bestConfidence).takeLast(PERFORMANCE_HISTORY),
                        overlay = metrics.overlays,
                        frameWidth = metrics.frameWidth,
                        frameHeight = metrics.frameHeight,
                        frameContrast = metrics.contrast,
                        latencyMs = metrics.latencyMs,
                        speedKmh = if (metrics.speedKmh > 0f) metrics.speedKmh else it.speedKmh,
                    )
                }
            }
        },
    )

    private val _state = MutableStateFlow(
        UiState(
            calibration = calibration,
            meta = meta,
            license = license,
            licenseSummary = license.summary(System.currentTimeMillis(), messages),
            canExport = license.canExport(),
            modelReady = detector.isReady,
            priceCatalog = container.priceCatalog,
            currencySymbol = docStrings.currencySymbol(container.priceCatalog.currency),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokenAtMs = 0L

    /** Смены, у которых итоги уже досчитаны в этом процессе (защита от цикла). */
    private val repairedSessions = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        viewModelScope.launch {
            repository.observeSessions().collect { sessions ->
                _state.update { it.copy(sessions = sessions) }
                repairInProgress(sessions)
                refreshArchiveTypes()
            }
        }
        initTts()
        refreshReadiness()
    }

    /** Обновляет структуру дефектов архива (тип → сколько) для сводки. */
    private suspend fun refreshArchiveTypes() {
        val state = _state.value
        val types = withContext(Dispatchers.IO) {
            runCatching {
                repository.archiveTypeCounts(
                    fromMs = state.periodFromMs ?: 0L,
                    toMs = state.periodToMs ?: Long.MAX_VALUE,
                )
            }.getOrDefault(emptyList())
        }.sortedByDescending { it.second }
        _state.update { state ->
            if (state.archiveTypes == types) state
            else state.copy(archiveTypes = types)
        }
    }

    /**
     * Период архива: [hoursBack] — часы от текущего момента (null — весь
     * архив). Сводка и структура дефектов пересчитываются на периоде.
     */
    fun setPeriod(hoursBack: Long?) {
        val now = System.currentTimeMillis()
        val from = hoursBack?.let { now - it * 3_600_000L }
        _state.update {
            it.copy(
                periodFromMs = from,
                periodToMs = if (from == null) null else now,
                periodHoursBack = hoursBack,
            )
        }
        viewModelScope.launch { refreshArchiveTypes() }
    }

    /** Смены текущего периода — для списка, сводки и экспорта архива. */
    fun sessionsInPeriod(): List<SessionEntity> {
        val state = _state.value
        val from = state.periodFromMs ?: return state.sessions
        val to = state.periodToMs ?: Long.MAX_VALUE
        return state.sessions.filter { it.startedAtMs in from..to }
    }

    /**
     * Сравнение «до/после»: выбираем две смены (максимум две), по вторым
     * из них считаем дельту — приёмка ремонта. Порядок выбора не важен:
     * «до» — всегда более ранняя смена.
     */
    fun toggleCompare(session: SessionEntity) {
        val ids = _state.value.compareIds
        val next = when {
            ids.contains(session.id) -> ids - session.id
            ids.size >= 2 -> listOf(ids.last(), session.id)
            else -> ids + session.id
        }
        _state.update { it.copy(compareIds = next, delta = null) }
        if (next.size == 2) buildDelta(next)
    }

    fun clearCompare() {
        _state.update {
            it.copy(compareIds = emptyList(), delta = null, compareReports = null)
        }
    }

    private fun buildDelta(ids: List<String>) {
        viewModelScope.launch {
            _state.update { it.copy(comparing = true) }
            val ordered = _state.value.sessions
                .filter { session -> session.id in ids }
                .sortedBy { it.startedAtMs }
            val result = withContext(Dispatchers.IO) {
                runCatching { ordered.map { session -> loadReport(session) } }
            }
            result.onSuccess { reports ->
                if (reports.size == 2) {
                    val sides = reports.map { report ->
                        ai.roadscan.core.report.SurveySide(
                            defects = report.defects,
                            areaM2 = report.estimate.totalAreaM2,
                            cost = report.estimate.total,
                        )
                    }
                    val delta = ai.roadscan.core.report.SessionDiff.of(sides[0], sides[1])
                    _state.update {
                        it.copy(
                            comparing = false,
                            delta = delta,
                            compareReports = reports[0] to reports[1],
                        )
                    }
                } else {
                    _state.update {
                        it.copy(comparing = false, message = str(R.string.compare_error, "—"))
                    }
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(comparing = false, message = str(R.string.compare_error, error.message ?: ""))
                }
            }
        }
    }

    /** Сторона сравнения: дефекты + итоги сметы, как для отчёта. */
    private suspend fun loadSide(session: SessionEntity): ai.roadscan.core.report.SurveySide {
        val report = loadReport(session)
        return ai.roadscan.core.report.SurveySide(
            defects = report.defects,
            areaM2 = report.estimate.totalAreaM2,
            cost = report.estimate.total,
        )
    }

    /**
     * Полный отчёт смены из базы: дефекты, кластеры, смета, реквизиты.
     * Общая база для сравнения «до/после» и экспорта архива.
     */
    private suspend fun loadReport(session: SessionEntity): ai.roadscan.core.agent.SessionReport {
        val defects = repository.loadDefects(session.id)
        val (meta, calib) = repository.metaOf(session)
        val stats = ai.roadscan.core.agent.SessionStats(
            confirmed = defects.count { it.confirmed },
            visualOnly = defects.count { !it.confirmed },
        )
        return ai.roadscan.core.agent.PostProcessingAgent(
            container.config,
            container.priceCatalog,
        ).process(
            sessionId = session.id,
            startedAtMs = session.startedAtMs,
            endedAtMs = if (session.endedAtMs > 0L) {
                session.endedAtMs
            } else {
                System.currentTimeMillis()
            },
            defects = defects,
            stats = stats,
            meta = meta,
            calibration = calib,
        )
    }

    /** Удаление смены со снимками; идущую смену удалять нельзя. */
    fun deleteSession(session: SessionEntity) {
        if (session.endedAtMs == 0L && _state.value.running) {
            _state.update { it.copy(message = str(R.string.session_delete_running)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { repository.deleteSession(session.id) }.isSuccess
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        message = if (ok) {
                            str(R.string.session_deleted)
                        } else {
                            str(R.string.compare_error, "—")
                        },
                    )
                }
            }
        }
    }

    /**
     * Полный архив документов: комплект (PDF + XLSX + GeoJSON + снимки)
     * каждой завершённой смены одним ZIP — на передачу заказчику разом.
     */
    fun exportArchive() {
        refreshLicense()
        if (!license.hasFeature("pdf")) {
            _state.update { it.copy(message = str(R.string.msg_feature_unavailable, "PDF")) }
            return
        }
        val sessions = sessionsInPeriod().filter {
            it.endedAtMs > 0L && !it.id.startsWith("DEMO")
        }
        if (sessions.isEmpty()) {
            _state.update { it.copy(message = str(R.string.archive_export_empty)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val application = getApplication<Application>()
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    val out = File(application.cacheDir, "exports/roadscan_archive_$stamp.zip")
                    val licensed = license.isLicensed(System.currentTimeMillis())
                    val entries = sessions.map { session ->
                        val report = loadReport(session)
                            .let { it.copy(meta = it.meta.copy(demo = !licensed)) }
                        val file = exports.export(report, ReportFormat.PACKAGE)
                        file.name to file
                    }
                    out.outputStream().use { stream ->
                        ai.roadscan.core.backup.BackupArchive.write(stream, entries)
                    }
                    out
                }
            }
            _state.update { it.copy(busy = false) }
            result.onSuccess { file ->
                _state.update { it.copy(message = str(R.string.msg_export_done, file.name)) }
                shareFile(file, "application/zip")
            }.onFailure { error ->
                _state.update {
                    it.copy(message = str(R.string.msg_export_error, error.message ?: "—"))
                }
            }
        }
    }

    /** Общий share: файл из кэша через FileProvider → системный лист. */
    private fun shareFile(file: File, mime: String) {
        runCatching {
            val application = getApplication<Application>()
            val uri = FileProvider.getUriForFile(
                application,
                "${application.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(application.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, str(R.string.share_chooser)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = intent.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            application.startActivity(chooser)
        }
    }

    /**
     * Досчитывает итоги незавершённой смены по фактически записанным
     * дефектам. Android мог убить процесс посреди смены: сами находки
     * дописаны ходу, а агрегаты строки (счётчики, площадь, смета) — нет,
     * и в списке смена висит с нулями. Выполняется один раз на смену.
     */
    private fun repairInProgress(sessions: List<SessionEntity>) {
        sessions
            .filter { it.endedAtMs == 0L && repairedSessions.add(it.id) }
            .forEach { session ->
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        val defects = repository.loadDefects(session.id)
                        if (defects.isEmpty()) return@runCatching
                        val (meta, calib) = repository.metaOf(session)
                        val stats = ai.roadscan.core.agent.SessionStats(
                            confirmed = defects.count { it.confirmed },
                            visualOnly = defects.count { !it.confirmed },
                        )
                        val report = ai.roadscan.core.agent.PostProcessingAgent(
                            container.config,
                            container.priceCatalog,
                        ).process(
                            sessionId = session.id,
                            startedAtMs = session.startedAtMs,
                            // 0 — смена остаётся «у процесі», итоги при этом по факту.
                            endedAtMs = 0L,
                            defects = defects,
                            stats = stats,
                            meta = meta,
                            calibration = calib,
                        )
                        repository.save(report)
                    }
                }
            }
    }

    /** Тюнинг устройства из автоподготовки; null — дефолтный профиль. */
    private var deviceTuning: ai.roadscan.core.calibration.DeviceTuning? = null

    /**
     * Автоподготовка (одна кнопка): оптика камеры из характеристик,
     * наклон по датчику, замер инференса, профиль работы. Всё офлайн.
     */
    fun runAutoSetup() {
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, message = null) }
            val runner = ai.roadscan.app.setup.AutoSetupRunner(getApplication())
            val result = runCatching {
                runner.run(measureInferenceMs = { measureInferenceMs() })
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { setup ->
                    deviceTuning = setup.tuning
                    container.settings.saveCalibration(setup.calibration)
                    calibration = setup.calibration
                    agent = buildAgent(setup.calibration)
                    _state.update {
                        it.copy(
                            busy = false,
                            calibration = setup.calibration,
                            message = setup.summary,
                        )
                    }
                    refreshReadiness()
                }.onFailure { error ->
                    _state.update {
                        it.copy(busy = false, message = "Автонастройка не удалась: ${error.message}")
                    }
                }
            }
        }
    }

    /** Медианная задержка инференса на пробных кадрах, мс. */
    private fun measureInferenceMs(): Double {
        val bitmap = android.graphics.Bitmap.createBitmap(
            1280,
            720,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(120, 120, 120))
        val frame = ai.roadscan.core.model.FrameData(
            timestampMs = System.currentTimeMillis(),
            location = null,
            cameraHeightM = 1.3f,
            cameraPitchDeg = 8f,
            imageWidth = 1280,
            imageHeight = 720,
            imageRef = bitmap,
        )
        val samples = mutableListOf<Double>()
        repeat(3) {
            detector.detect(frame)
            samples += detector.lastLatencyMs
        }
        bitmap.recycle()
        return samples.sorted().let { it[it.size / 2] }
    }

    private fun buildAgent(profile: CalibrationProfile): RoadScanAgent {
        val base = deviceTuning?.let { tuning ->
            container.config.copy(maxAnalysisFps = tuning.analysisFps)
        } ?: container.config
        return buildAgent(profile, base)
    }

    private fun buildAgent(profile: CalibrationProfile, config: ai.roadscan.core.agent.AgentConfig): RoadScanAgent = RoadScanAgent(
        config = profile.applyTo(config),
        detector = detector,
        eventListener = { event -> onAgentEvent(event) },
        messages = messages,
    )

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    // --- смена ---

    fun startSession() {
        if (_state.value.running) return
        refreshLicense()
        if (!license.canExport()) {
            _state.update { it.copy(message = str(R.string.msg_trial_over)) }
            return
        }
        if (license.payload?.maxSessions?.let { it > 0 && settings.sessionsTotal() >= it } == true) {
            _state.update { it.copy(message = str(R.string.msg_session_limit)) }
            return
        }

        if (!detector.isReady) {
            _state.update { it.copy(modelReady = false) }
        }

        // Стартуем с актуальной калибровкой и реквизитами.
        calibration = settings.calibration()
        meta = settings.reportMeta()
        agent = buildAgent(calibration)
        postProcessing = PostProcessingAgent(agent.config, container.priceCatalog)
        analyzer.resetTracking()

        location.start()
        val snapshot = telemetry.snapshot()
        val env = EnvironmentSnapshot(
            gpsAccuracyM = location.accuracyM,
            freeDiskMb = snapshot.freeDiskMb,
            batteryPercent = snapshot.batteryPercent,
            batteryTempC = snapshot.batteryTempC,
            cpuTempC = snapshot.cpuTempC,
        )
        val result = agent.startSession(env)
        if (!result.started) {
            location.stop()
            _state.update { it.copy(agentState = agent.state) }
            return
        }
        sensors.start()
        _state.update {
            it.copy(
                running = true,
                agentState = agent.state,
                calibration = calibration,
                meta = meta,
                overlay = emptyList(),
                track = emptyList(),
                summary = emptyList(),
                defects = emptyList(),
                selectedDefectId = null,
                performance = emptyList(),
            )
        }
        // Смена заводится в базе сразу: переживает убийство процесса.
        persistedDefectIds.clear()
        val sessionId = agent.currentSessionId
        if (sessionId != null) {
            viewModelScope.launch {
                runCatching {
                    repository.beginSession(
                        sessionId = sessionId,
                        startedAtMs = agent.sessionStartedAtMs,
                        metaJson = ReportMeta.toJson(meta),
                        calibrationJson = ai.roadscan.core.calibration.CalibrationProfile.toJson(calibration),
                    )
                }
            }
        }
        startTicker()
    }

    fun stopSession() {
        ticker?.cancel()
        ticker = null
        sensors.stop()

        val data = agent.finishSession()
        if (data == null) {
            location.stop()
            _state.update { it.copy(running = false, agentState = agent.state) }
            return
        }
        val report = postProcessing.process(
            sessionId = data.sessionId,
            startedAtMs = data.startedAtMs,
            endedAtMs = data.endedAtMs,
            defects = data.defects,
            stats = data.stats,
            meta = meta.copy(demo = !license.isLicensed(System.currentTimeMillis())),
            calibration = calibration,
        )
        location.stop()
        agent.postProcessingDone()
        settings.registerSession()
        refreshLicense()
        _state.update {
            it.copy(
                running = false,
                agentState = agent.state,
                report = report,
                confirmed = report.stats.confirmed,
                visualOnly = report.stats.visualOnly,
                overlay = emptyList(),
                backgroundMode = false,
                batteryOffer = false,
                events = (listOf(
                    str(
                        R.string.msg_session_done,
                        report.clusters.size,
                        report.estimate.total.toLong(),
                        docStrings.currencySymbol(report.estimate.currency),
                    ),
                ) + it.events).take(8),
                meta = report.meta,
            )
        }
        viewModelScope.launch {
            runCatching { repository.save(report) }
        }
    }

    /**
     * Фоновый режим (ТЗ п. 3.2): анализ видео выключается, камера не грузит
     * процессор, телеметрия и путевой лог продолжают писаться. Смена не
     * прерывается — оператор может вернуть обычный режим.
     */
    fun enterBackgroundMode() {
        if (!_state.value.running) return
        agent.setVisionEnabled(false)
        _state.update {
            it.copy(backgroundMode = true, batteryOffer = false)
        }
        speak(str(R.string.msg_background_mode), force = true)
    }

    /** Вернуть обычный режим анализа видео. */
    fun exitBackgroundMode() {
        if (!_state.value.running) return
        agent.setVisionEnabled(true)
        _state.update {
            it.copy(backgroundMode = false)
        }
    }

    /** Отклонить предложение при низком заряде — смена продолжается как есть. */
    fun dismissBatteryOffer() {
        _state.update { it.copy(batteryOffer = false) }
    }

    /**
     * Выбор дефекта для инспектора (правая панель).
     */
    fun selectDefect(id: String?) {
        _state.update { it.copy(selectedDefectId = id) }
    }

    /**
     * Решение оператора по находке.
     *
     * Отклонённый дефект не попадает в ведомость и смету; подтверждённый
     * оператором попадает, даже если автоматика удар не увидела. Решение
     * сохраняется в базу и переживает перезапуск приложения.
     */
    /** Привязывает снимок находки (появился сразу после обнаружения). */
    fun attachPhoto(id: String, path: String) {
        _state.update { state ->
            state.copy(
                defects = state.defects.map { if (it.id == id) it.copy(photoPath = path) else it },
            )
        }
    }

    fun setDefectReview(id: String, decision: ReviewDecision) {
        agent.setReview(id, decision)
        _state.update { state ->
            state.copy(
                defects = state.defects.map { if (it.id == id) it.copy(review = decision) else it },
            )
        }
        viewModelScope.launch {
            runCatching { repository.setReview(id, decision) }
        }
    }

    /**
     * Проверка готовности комплекта перед сменой: модель, камера, датчик,
     * GNSS, место, заряд, лицензия. Всё офлайн.
     */
    fun runDiagnostics() {
        val application = getApplication<Application>()
        viewModelScope.launch {
            val checks = withContext(Dispatchers.IO) {
                ai.roadscan.app.diagnostics.Diagnostics.run(
                    context = application,
                    detector = detector,
                    location = location,
                    telemetry = telemetry,
                    license = license,
                    config = container.config,
                    sessionCount = container.settings.sessionsTotal(),
                )
            }
            _state.update { it.copy(diagnostics = checks) }
            refreshReadiness(checks)
        }
    }

    /**
     * Пересчитывает готовность к смене: разрешения, лицензия, калибровка,
     * реквизиты и состояние оборудования.
     */
    private fun refreshReadiness(
        checks: List<ai.roadscan.app.diagnostics.DiagnosticCheck> = _state.value.diagnostics,
    ) {
        val application = getApplication<Application>()
        val permissions = ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                application,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        val licenseNow = container.settings.licenseState()
        val calibration = container.settings.calibration()
        val meta = container.settings.reportMeta()
        val equipmentBlocking = checks.any { !it.ok && it.blocking }

        val readiness = ReadinessEvaluator.evaluate(
            permissionsGranted = permissions,
            licenseActive = licenseNow.isLicensed(System.currentTimeMillis()),
            calibrationReady = calibration.hasOptics,
            documentsFilled = meta.isFilled,
            equipmentReady = checks.none { !it.ok && it.blocking } && checks.isNotEmpty(),
            equipmentBlocking = equipmentBlocking,
        )
        _state.update { it.copy(readiness = readiness) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive && _state.value.running) {
                val snapshot = telemetry.snapshot()
                agent.onVehicleTelemetry(snapshot)
                if (agent.state == AgentState.CALIBRATING) {
                    agent.updateEnvironment(
                        EnvironmentSnapshot(
                            gpsAccuracyM = location.accuracyM,
                            freeDiskMb = snapshot.freeDiskMb,
                            batteryPercent = snapshot.batteryPercent,
                            batteryTempC = snapshot.batteryTempC,
                            cpuTempC = snapshot.cpuTempC,
                        ),
                    )
                }
                val defects = agent.acceptedDefects
                persistNewDefects(defects)
                val summary = defects.groupBy { it.type }
                    .map { (type, group) ->
                        DefectSummary(
                            type = type,
                            count = group.size,
                            avgConfidence = group.map { it.visionConfidence }.average().toFloat(),
                        )
                    }
                    .sortedByDescending { it.count }
                val point = location.latest
                _state.update {
                    val track = if (point != null) {
                        (it.track + point).takeLast(300)
                    } else {
                        it.track
                    }
                    it.copy(
                        agentState = agent.state,
                        targetFps = agent.lastGovernorDecision.targetFps,
                        speedKmh = if (snapshot.speedKmh > 0f) snapshot.speedKmh else it.speedKmh,
                        batteryPercent = snapshot.batteryPercent,
                        cpuTempC = snapshot.cpuTempC,
                        inferenceBackend = detector.backend.name,
                        gpsAccuracyM = location.accuracyM,
                        freeDiskMb = snapshot.freeDiskMb,
                        frames = agent.statsSnapshot().framesAnalyzed,
                        confirmed = defects.count { d -> d.confirmed },
                        visualOnly = defects.count { d -> !d.confirmed },
                        summary = summary,
                        defects = defects,
                        track = track,
                        latitude = point?.lat ?: it.latitude,
                        longitude = point?.lon ?: it.longitude,
                        street = location.street ?: it.street,
                        city = location.city ?: it.city,
                    )
                }
                delay(1_000)
            }
        }
    }

    /**
     * Дописывает в базу дефекты, найденные с прошлого тика.
     *
     * Смена не должна теряться, если Android убьёт процесс: пишем находки
     * по ходу, а не только в конце. Идентификаторы уже записанных дефектов
     * держим в памяти, поэтому повторная запись не случается.
     */
    private fun persistNewDefects(defects: List<ai.roadscan.core.model.DefectRecord>) {
        val fresh = defects.filter { persistedDefectIds.add(it.id) }
        if (fresh.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.appendDefects(fresh) }
        }
    }

    private fun onAgentEvent(event: AgentEvent) {
        when (event) {
            // ТЗ п. 3.2: при низком заряде агент предлагает выбрать режим —
            // фоновый с минимальным энергопотреблением или завершение смены.
            is AgentEvent.BatteryLow -> _state.update {
                it.copy(batteryOffer = true, batteryOfferPercent = event.percent)
            }

            is AgentEvent.VisionModeChanged -> _state.update {
                it.copy(backgroundMode = !event.enabled)
            }

            else -> Unit
        }
        val (text, speak) = when (event) {
            is AgentEvent.VoicePrompt -> event.message to true
            is AgentEvent.LensDirty -> messages.lensWarning to true
            is AgentEvent.Error -> event.message to false
            is AgentEvent.BatteryLow -> str(R.string.msg_battery, event.percent) to true
            is AgentEvent.ThermalThrottled ->
                str(R.string.msg_thermal_throttle, event.targetFps, event.cpuTempC.toInt()) to false
            is AgentEvent.ThermalRecovered -> str(R.string.msg_thermal_recovered, event.targetFps) to false
            else -> null to false
        }
        if (text == null) return
        _state.update { it.copy(events = (listOf(text) + it.events).take(8)) }
        if (speak) speak(text)
    }

    // --- настройки ---

    fun saveCalibration(profile: CalibrationProfile) {
        calibration = profile.copy(calibratedAtMs = System.currentTimeMillis())
        settings.saveCalibration(calibration)
        postProcessing = PostProcessingAgent(calibration.applyTo(container.config), container.priceCatalog)
        val error = calibration.validationErrors(messages).firstOrNull()
        _state.update {
            it.copy(
                calibration = calibration,
                message = if (error == null) {
                    str(R.string.msg_calibration_saved)
                } else {
                    str(R.string.msg_calibration_error, error)
                },
            )
        }
    }

    fun saveMeta(meta: ReportMeta) {
        this.meta = meta.copy(demo = false)
        settings.saveReportMeta(this.meta)
        _state.update { it.copy(meta = this.meta, message = str(R.string.msg_meta_saved)) }
    }

    fun activateLicense(key: String) {
        val code = settings.activate(key)
        refreshLicense()
        val text = if (code == null) {
            str(R.string.msg_license_activated, license.summary(System.currentTimeMillis(), messages))
        } else {
            messages.licenseError(code)
        }
        _state.update { it.copy(message = text) }
    }

    fun deactivateLicense() {
        settings.deactivate()
        refreshLicense()
        _state.update { it.copy(message = str(R.string.msg_license_removed)) }
    }

    fun refreshLicense() {
        license = settings.licenseState()
        _state.update {
            it.copy(
                license = license,
                licenseSummary = license.summary(System.currentTimeMillis(), messages),
                canExport = license.canExport(),
            )
        }
    }

    // --- экспорт ---

    /**
     * Акт приёмки ремонта: PDF сравнения двух обследований с подписью
     * оператора — документ, который подписывают на приёмке работ.
     */
    fun exportComparePdf() {
        val (before, after, delta) = _state.value.let {
            Triple(it.compareReports?.first, it.compareReports?.second, it.delta)
        }
        if (before == null || after == null || delta == null) {
            _state.update { it.copy(message = str(R.string.compare_error, "—")) }
            return
        }
        refreshLicense()
        if (!license.hasFeature("pdf")) {
            _state.update { it.copy(message = str(R.string.msg_feature_unavailable, "PDF")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val stampedBefore = before.copy(
                meta = before.meta.copy(demo = !license.isLicensed(System.currentTimeMillis())),
            )
            val stampedAfter = after.copy(
                meta = after.meta.copy(demo = !license.isLicensed(System.currentTimeMillis())),
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = exports.exportComparison(stampedBefore, stampedAfter, delta)
                    file to exports.shareIntent(file, ReportFormat.PDF)
                }
            }
            _state.update { it.copy(busy = false) }
            result.onSuccess { (file, intent) ->
                _state.update { it.copy(message = str(R.string.msg_export_done, file.name)) }
                runCatching {
                    val chooser = Intent.createChooser(intent, str(R.string.share_chooser)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        clipData = intent.clipData
                    }
                    getApplication<Application>().startActivity(chooser)
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(message = str(R.string.msg_export_error, error.message ?: "—"))
                }
            }
        }
    }

    fun export(format: ReportFormat) {
        val report = _state.value.report ?: return
        refreshLicense()
        val feature = when (format) {
            ReportFormat.PDF -> "pdf"
            ReportFormat.XLSX -> "xlsx"
            ReportFormat.GEOJSON -> "geojson"
            ReportFormat.PACKAGE -> "pdf"
        }
        if (!license.hasFeature(feature)) {
            _state.update { it.copy(message = str(R.string.msg_feature_unavailable, format.name)) }
            return
        }
        val stamped = report.copy(meta = report.meta.copy(demo = !license.isLicensed(System.currentTimeMillis())))
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = exports.export(stamped, format)
                    file to exports.shareIntent(file, format)
                }
            }
            _state.update { it.copy(busy = false) }
            result.onSuccess { (file, intent) ->
                _state.update { it.copy(message = str(R.string.msg_export_done, file.name)) }
                runCatching {
                    val chooser = Intent.createChooser(intent, str(R.string.share_chooser)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // Грант на чтение файла передаём и листу выбора: иначе
                        // выбранное приложение не сможет прочитать документ.
                        clipData = intent.clipData
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    getApplication<Application>().startActivity(chooser)
                }
            }.onFailure {
                _state.update { it.copy(message = str(R.string.msg_export_error, it.message ?: "—")) }
            }
        }
    }

    /**
     * Резервная копия: база смен и настройки одним zip-файлом.
     * Дальше обычный share — куда хочется: облако, почта, Telegram.
     */
    fun backupData() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val application = getApplication<Application>()
                    val file = ai.roadscan.app.data.BackupManager(application).create {
                        // WAL → основной файл, чтобы копия не отстала от журнала.
                        container.database.openHelper.writableDatabase
                            .query("PRAGMA wal_checkpoint(TRUNCATE)")
                            .use { it.moveToFirst() }
                    }
                    file
                }
            }
            _state.update { it.copy(busy = false) }
            result.onSuccess { file ->
                _state.update { it.copy(message = str(R.string.backup_done, file.name)) }
                shareFile(file, "application/zip")
            }.onFailure { error ->
                _state.update { it.copy(message = str(R.string.backup_error, error.message ?: "—")) }
            }
        }
    }

    /**
     * Восстановление из копии: архив раскладывается в staging, затем
     * процесс завершается — при следующем запуске файлы подменяются
     * до открытия базы (см. BackupManager.applyPendingRestore).
     */
    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val staged = withContext(Dispatchers.IO) {
                runCatching { ai.roadscan.app.data.BackupManager(getApplication()).stageRestore(uri) }
            }
            if (staged.isFailure) {
                _state.update {
                    it.copy(busy = false, message = str(R.string.backup_error, staged.exceptionOrNull()?.message ?: "—"))
                }
                return@launch
            }
            _state.update { it.copy(busy = false, message = str(R.string.backup_restore_staged)) }
            // Даём прочитать сообщение — и завершаем процесс: подменить
            // файлы открытой базы и prefs внутри живого процесса нельзя.
            kotlinx.coroutines.delay(1_500)
            Runtime.getRuntime().exit(0)
        }
    }

    fun openSession(session: SessionEntity) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val defects = withContext(Dispatchers.IO) { repository.loadDefects(session.id) }
            val (sessionMeta, sessionCalibration) = repository.metaOf(session)
            // У незавершённой смены итоги в строке ещё нулевые — считаем по факту.
            val stats = if (session.endedAtMs > 0L) {
                SessionStats(
                    confirmed = session.confirmedCount,
                    visualOnly = session.visualOnlyCount,
                )
            } else {
                SessionStats(
                    confirmed = defects.count { it.confirmed },
                    visualOnly = defects.count { !it.confirmed },
                )
            }
            val report = postProcessing.process(
                sessionId = session.id,
                startedAtMs = session.startedAtMs,
                endedAtMs = if (session.endedAtMs > 0L) session.endedAtMs else System.currentTimeMillis(),
                defects = defects,
                stats = stats,
                meta = sessionMeta,
                calibration = sessionCalibration ?: calibration,
            )
            _state.update { it.copy(report = report, busy = false) }
        }
    }

    /** Сохраняет правки каталога цен (свои расценки по договору). */
    fun savePrices(overrides: Map<String, Double>) {
        container.updatePrices(overrides)
        _state.update {
            it.copy(priceCatalog = container.priceCatalog, message = str(R.string.prices_saved))
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * Демонстрационный отчёт: синтетическая смена + полный контур
     * пост-обработки (кластеры, смета) и экспорт. Не попадает в базу сессий,
     * поэтому не искажает статистику лицензии и реальные смены.
     */
    fun loadDemoReport() {
        if (_state.value.running) {
            _state.update { it.copy(message = str(R.string.msg_finish_first)) }
            return
        }
        val data = DemoSession.build()
        val demoMeta = if (meta.isFilled) meta else DemoSession.defaultMeta()
        val report = postProcessing.process(
            sessionId = data.sessionId,
            startedAtMs = data.startedAtMs,
            endedAtMs = data.endedAtMs,
            defects = data.defects,
            stats = data.stats,
            meta = demoMeta.copy(demo = !license.isLicensed(System.currentTimeMillis())),
            calibration = calibration,
        )
        _state.update {
            it.copy(
                report = report,
                confirmed = report.stats.confirmed,
                visualOnly = report.stats.visualOnly,
                message = str(
                    R.string.msg_demo_ready,
                    report.clusters.size,
                    report.estimate.total.toLong(),
                    docStrings.currencySymbol(report.estimate.currency),
                ),
            )
        }
    }

    // --- голосовые подсказки ---

    private fun initTts() {
        val engine = TextToSpeech(getApplication()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("uk", "UA")
                ttsReady = true
            }
        }
        tts = engine
    }

    /** Не спамим: обычные подсказки — не чаще раза в 6 секунд, критические — сразу. */
    private fun speak(text: String, force: Boolean = false) {
        if (!ttsReady) return
        val now = System.currentTimeMillis()
        val critical = force ||
            text.contains("Протріть", ignoreCase = true) ||
            text.contains("Протрите", ignoreCase = true) ||
            text.contains("заряд", ignoreCase = true) ||
            text.contains("Заряд", ignoreCase = true)
        if (!critical && now - lastSpokenAtMs < 6_000) return
        lastSpokenAtMs = now
        tts?.speak(text, if (critical) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "rs-$now")
    }

    override fun onCleared() {
        ticker?.cancel()
        sensors.stop()
        location.stop()
        detector.close()
        roadMask.close()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onCleared()
    }
}
