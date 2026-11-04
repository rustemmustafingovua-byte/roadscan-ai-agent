package ai.roadscan.app.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.roadscan.app.R
import ai.roadscan.app.RoadScanApp
import ai.roadscan.app.perception.YoloV8Detector
import ai.roadscan.app.video.VideoAnalyzer
import ai.roadscan.app.video.VideoBrander
import ai.roadscan.app.video.VideoStreamDecoder
import ai.roadscan.app.video.VideoInfo
import ai.roadscan.app.video.VideoMontageRenderer
import ai.roadscan.app.video.VideoProbe
import ai.roadscan.app.video.VideoTexts
import ai.roadscan.core.agent.PostProcessingAgent
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.video.MontageMode
import ai.roadscan.core.video.VideoSurveyPlanner
import ai.roadscan.core.video.MontagePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

enum class VideoStage { IDLE, PROBING, ANALYZING, RENDERING, DONE, ERROR }

/** Режим видеоконтура: ролик-обзор или обследование участка. */
enum class VideoMode { MONTAGE, SURVEY }

/** Состояние одного видео в очереди обследования. */
enum class SurveyItemState { WAITING, ANALYZING, DONE, FAILED }

data class SurveyQueueItem(
    val id: String,
    val uri: android.net.Uri,
    val name: String,
    val info: VideoInfo? = null,
    val state: SurveyItemState = SurveyItemState.WAITING,
    val defects: Int = 0,
    val confirmed: Int = 0,
    val error: String? = null,
) {
    val isRunning: Boolean get() = state == SurveyItemState.ANALYZING
}

data class VideoSummary(
    val defects: Int,
    val segments: Int,
    val durationMs: Long,
    val byType: List<Pair<DefectType, Int>>,
    val empty: Boolean,
)

data class VideoUiState(
    val info: VideoInfo? = null,
    val mode: MontageMode = MontageMode.HIGHLIGHTS,
    val stage: VideoStage = VideoStage.IDLE,
    val progress: Float = 0f,
    val stageLabel: String = "",
    val result: File? = null,
    val summary: VideoSummary? = null,
    val message: String? = null,
    val demoWatermark: Boolean = false,
    /** Режим работы видеоконтура. */
    val purpose: VideoMode = VideoMode.MONTAGE,
    /** Очередь видео для обследования участка. */
    val surveyQueue: List<SurveyQueueItem> = emptyList(),
    /** Итог обследования: смена с ведомостью и сметой. */
    val surveySessionId: String? = null,
    val surveyDefects: Int = 0,
    val surveyConfirmed: Int = 0,
    val surveyAreaM2: Double = 0.0,
) {
    val busy: Boolean
        get() = stage == VideoStage.PROBING ||
            stage == VideoStage.ANALYZING ||
            stage == VideoStage.RENDERING
}

/**
 * Видеостудия: сырое видео с телефона → детекция дефектов → автоматический
 * монтаж готового ролика. Всё офлайн, без загрузки на сервер.
 */
class VideoViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as RoadScanApp).container
    private val detectorLazy = lazy { YoloV8Detector(application) }
    private val photoStore = ai.roadscan.app.perception.DefectPhotoStore(application)
    private var job: Job? = null
    private var lastPercent = -1

    private val _state = MutableStateFlow(VideoUiState())
    val state: StateFlow<VideoUiState> = _state.asStateFlow()

    fun select(uri: Uri) {
        job?.cancel()
        deleteResult()
        _state.update {
            it.copy(
                info = null,
                stage = VideoStage.PROBING,
                progress = 0f,
                stageLabel = str(R.string.video_stage_probe),
                result = null,
                summary = null,
                message = null,
            )
        }
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { VideoProbe.probe(getApplication(), uri) }
            if (info == null) {
                _state.update {
                    it.copy(
                        stage = VideoStage.ERROR,
                        stageLabel = "",
                        message = str(R.string.video_error, "unsupported video"),
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        info = info,
                        stage = VideoStage.IDLE,
                        progress = 0f,
                        stageLabel = "",
                        demoWatermark = container.settings.licenseState().demoWatermark(),
                    )
                }
            }
        }
    }

    fun setMode(mode: MontageMode) {
        if (_state.value.busy) return
        _state.update { it.copy(mode = mode) }
    }

    fun start() {
        if (job?.isActive == true) return
        val info = _state.value.info ?: return
        val mode = _state.value.mode
        val application = getApplication<Application>()
        val output = VideoMontageRenderer.outputFile(File(application.cacheDir, "videos"))
        val demo = container.settings.licenseState().demoWatermark()
        lastPercent = -1

        _state.update {
            it.copy(
                stage = VideoStage.ANALYZING,
                progress = 0f,
                stageLabel = str(R.string.video_stage_prepare),
                result = null,
                summary = null,
                message = null,
                demoWatermark = demo,
            )
        }

        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                // Декодер создаём внутри try: у неподдерживаемого кодека его
                // конструктор бросает, и вне try исключение ушло бы из
                // viewModelScope в глобальный обработчик — то есть в краш.
                val decoder = VideoStreamDecoder(application, info)
                val texts = texts()
                val analyzer = VideoAnalyzer(detector = detectorLazy.value::detect)
                val analysis = try {
                    analyzer.analyze(
                        decoder = decoder,
                        info = info,
                        isActive = { isActive },
                        onProgress = { fraction ->
                            publish(
                                VideoStage.ANALYZING,
                                fraction = 0.02f + fraction * 0.48f,
                                labelRes = R.string.video_stage_analyze,
                                labelFraction = fraction,
                            )
                        },
                    )
                } finally {
                    decoder.close()
                }

                if (!isActive) throw CancellationException("cancelled")

                val plan = MontagePlanner.plan(analysis, mode)
                val renderer = VideoMontageRenderer(application, info, plan, texts, demo)
                val file = renderer.render(
                    output = output,
                    isActive = { isActive },
                    onProgress = { fraction ->
                        publish(
                            VideoStage.RENDERING,
                            fraction = 0.5f + fraction * 0.5f,
                            labelRes = R.string.video_stage_render,
                            labelFraction = fraction,
                        )
                    },
                )

                val videoMs = if (plan.isEmpty) {
                    VideoBrander.NO_DEFECTS_MS
                } else {
                    VideoBrander.INTRO_MS + plan.videoDurationMs + VideoBrander.OUTRO_MS
                }
                _state.update {
                    it.copy(
                        stage = VideoStage.DONE,
                        progress = 1f,
                        stageLabel = "",
                        result = file,
                        summary = VideoSummary(
                            defects = plan.eventCount,
                            segments = plan.segments.size,
                            durationMs = videoMs,
                            byType = renderer.summary(),
                            empty = plan.isEmpty,
                        ),
                        message = if (plan.isEmpty) str(R.string.video_no_defects_done) else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                runCatching { output.delete() }
                _state.update {
                    it.copy(stage = VideoStage.IDLE, progress = 0f, stageLabel = "", result = null)
                }
                throw cancelled
            } catch (error: Throwable) {
                runCatching { output.delete() }
                _state.update {
                    it.copy(
                        stage = VideoStage.ERROR,
                        progress = 0f,
                        stageLabel = "",
                        result = null,
                        message = str(
                            R.string.video_error,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** «Новый ролик»: файл больше не нужен, оставляем выбранное видео. */
    fun reset() {
        job?.cancel()
        deleteResult()
        _state.update {
            it.copy(
                stage = VideoStage.IDLE,
                progress = 0f,
                stageLabel = "",
                result = null,
                summary = null,
                message = null,
            )
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    // ------------------------------------------------------------ обследование

    /** Переключение режима: ролик-обзор или обследование участка. */
    fun setPurpose(mode: VideoMode) {
        if (_state.value.busy) return
        _state.update { it.copy(purpose = mode) }
    }

    /**
     * Добавляет видео в очередь обследования.
     *
     * Несколько роликов — это несколько участков или продолжение одного:
     * дефекты из всех файлов сводятся в одну ведомость и одну смету.
     */
    fun addToSurvey(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val existing = _state.value.surveyQueue
        val stamp = System.currentTimeMillis()
        val items = uris.mapIndexed { index, uri ->
            SurveyQueueItem(
                id = "V$stamp-$index",
                uri = uri,
                name = uri.lastPathSegment ?: "video-$index",
            )
        }
        _state.update {
            it.copy(
                purpose = VideoMode.SURVEY,
                surveyQueue = existing + items,
                message = null,
            )
        }
        // Зонд сразу: оператор видит длительность и разрешение каждого ролика.
        viewModelScope.launch {
            for (item in items) {
                val info = withContext(Dispatchers.IO) {
                    VideoProbe.probe(getApplication(), item.uri)
                }
                updateQueueItem(item.id) {
                    if (info == null) {
                        it.copy(state = SurveyItemState.FAILED, error = "unsupported video")
                    } else {
                        // Имя берём из зонда: Photo Picker отдаёт внутренний id
                        // («1000000018.mp4»), в ведомости такое не годится.
                        it.copy(info = info, name = info.displayName)
                    }
                }
            }
        }
    }

    fun removeFromSurvey(id: String) {
        if (_state.value.busy) return
        _state.update { it.copy(surveyQueue = it.surveyQueue.filterNot { item -> item.id == id }) }
    }

    fun clearSurvey() {
        if (_state.value.busy) return
        _state.update {
            it.copy(
                surveyQueue = emptyList(),
                surveySessionId = null,
                surveyDefects = 0,
                surveyConfirmed = 0,
                surveyAreaM2 = 0.0,
            )
        }
    }

    private fun updateQueueItem(id: String, transform: (SurveyQueueItem) -> SurveyQueueItem) {
        _state.update {
            it.copy(surveyQueue = it.surveyQueue.map { item -> if (item.id == id) transform(item) else item })
        }
    }

    /**
     * Обследование: анализ всех видео очереди -> ведомость дефектов + смета.
     *
     * Подтверждение дефекта в видео — устойчивое присутствие в кадрах:
     * настоящая яма видна в ряде соседних сэмплов, а блик или тень — нет.
     * Результат сохраняется как смена и открывается во вкладке «Звіт».
     */
    fun startSurvey() {
        if (job?.isActive == true) return
        val queue = _state.value.surveyQueue
        if (queue.isEmpty()) return
        val application = getApplication<Application>()
        val section = container.settings.reportMeta().section()
        val calibration = container.settings.calibration()
        val meta = container.settings.reportMeta()
        val license = container.settings.licenseState()

        _state.update {
            it.copy(
                purpose = VideoMode.SURVEY,
                stage = VideoStage.ANALYZING,
                progress = 0f,
                stageLabel = str(R.string.video_stage_analyze, 0),
                message = null,
                surveySessionId = null,
                surveyDefects = 0,
                surveyConfirmed = 0,
                surveyAreaM2 = 0.0,
            )
        }

        job = viewModelScope.launch(Dispatchers.Default) {
            val records = mutableListOf<ai.roadscan.core.model.DefectRecord>()
            // Идентификатор смены создаём до анализа: записи дефектов и сама
            // смена должны нести один id, иначе ведомость откроется пустой.
            val sessionId = "SUR-" + System.currentTimeMillis().toString().takeLast(8)
            var done = 0
            for (item in queue) {
                if (!isActive) throw CancellationException("survey cancelled")
                updateQueueItem(item.id) { it.copy(state = SurveyItemState.ANALYZING, error = null) }
                try {
                    val info = item.info
                    if (info == null) {
                        updateQueueItem(item.id) {
                            it.copy(state = SurveyItemState.FAILED, error = "unsupported video")
                        }
                        done++
                        continue
                    }
                    val decoder = VideoStreamDecoder(application, info)
                    val analysis = try {
                        VideoAnalyzer(detector = detectorLazy.value::detect).analyze(
                            decoder = decoder,
                            info = info,
                            isActive = { isActive },
                            onProgress = { fraction ->
                                publish(
                                    VideoStage.ANALYZING,
                                    fraction = (done + fraction) / queue.size,
                                    labelRes = R.string.video_stage_analyze,
                                )
                            },
                            onPhoto = { detection, bitmap ->
                                // Снимок из уже разобранного кадра: отдельный проход
                                // по ролику ради картинки стоил бы десятков секунд.
                                photoStore.save("t${detection.timeMs}", bitmap)
                            },
                        )
                    } finally {
                        decoder.close()
                    }

                    val defects = VideoSurveyPlanner.plan(
                        analysis = analysis,
                        section = section,
                        calibration = calibration,
                        // Глубину в видео не измерить: берём базовую из
                        // калибровки — ту же, что и для визуальных находок.
                        config = ai.roadscan.core.video.SurveyConfig(
                            assumedDepthMm = calibration.depthBaseMm,
                        ),
                        width = info.width,
                        height = info.height,
                        sessionId = sessionId,
                    )
                    // Снимок находки подставлен планером из разобранных кадров.
                    records += VideoSurveyPlanner.toRecords(defects, sessionId)
                    updateQueueItem(item.id) {
                        it.copy(
                            state = SurveyItemState.DONE,
                            defects = defects.size,
                            confirmed = defects.count { d -> d.confirmed },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    updateQueueItem(item.id) { it.copy(state = SurveyItemState.WAITING) }
                    throw cancelled
                } catch (error: Throwable) {
                    updateQueueItem(item.id) {
                        it.copy(
                            state = SurveyItemState.FAILED,
                            error = error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
                done++
                publish(
                    VideoStage.ANALYZING,
                    fraction = done.toFloat() / queue.size,
                    labelRes = R.string.video_stage_analyze,
                )
            }

            if (!isActive) throw CancellationException("survey cancelled")

            val sessionMeta = meta.copy(demo = !license.isLicensed(System.currentTimeMillis()))
            val report = PostProcessingAgent(calibration.applyTo(container.config), container.priceCatalog)
                .process(
                    sessionId = sessionId,
                    startedAtMs = System.currentTimeMillis() - queue.sumOf { it.info?.durationMs ?: 0L },
                    endedAtMs = System.currentTimeMillis(),
                    defects = records,
                    stats = ai.roadscan.core.agent.SessionStats(
                        confirmed = records.count { it.confirmed },
                        visualOnly = records.count { !it.confirmed },
                    ),
                    meta = sessionMeta,
                    calibration = calibration,
                )
            withContext(Dispatchers.IO) { runCatching { container.repository.save(report) } }

            _state.update {
                it.copy(
                    stage = VideoStage.DONE,
                    progress = 1f,
                    stageLabel = "",
                    surveySessionId = sessionId,
                    surveyDefects = records.size,
                    surveyConfirmed = records.count { r -> r.confirmed },
                    surveyAreaM2 = report.estimate.totalAreaM2,
                    message = str(
                        R.string.video_survey_done,
                        records.size,
                        records.count { r -> r.confirmed },
                    ),
                )
            }
        }
    }

    fun share() {
        val file = _state.value.result ?: return
        val application = getApplication<Application>()
        val uri = FileProvider.getUriForFile(
            application,
            "${application.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData обязателен: без него лист выбора не передаёт URI-грант
            // и выбранное приложение получает Permission Denial.
            clipData = ClipData.newUri(application.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, str(R.string.video_share_chooser)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = intent.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { application.startActivity(chooser) }
    }

    /**
     * @param fraction общий прогресс ролика (для полосы),
     * @param labelFraction прогресс текущего этапа (для подписи «Аналіз кадрів: N%»).
     */
    private fun publish(
        stage: VideoStage,
        fraction: Float,
        labelRes: Int,
        labelFraction: Float = fraction,
    ) {
        val percent = (labelFraction * 100).roundToInt().coerceIn(0, 100)
        val current = _state.value
        if (current.stage == stage && percent == lastPercent && abs(current.progress - fraction) < 0.004f) {
            return
        }
        lastPercent = percent
        _state.update {
            it.copy(
                stage = stage,
                progress = fraction.coerceIn(0f, 1f),
                stageLabel = str(labelRes, percent),
            )
        }
    }

    private fun deleteResult() {
        _state.value.result?.let { runCatching { it.delete() } }
    }

    private fun str(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun texts(): VideoTexts {
        val res = getApplication<Application>().resources
        return VideoTexts(
            title = res.getString(R.string.hud_title),
            brandSubtitle = res.getString(R.string.video_brand_subtitle),
            introKicker = res.getString(R.string.video_intro_kicker),
            introTitle = res.getString(R.string.video_intro_title),
            introSubtitle = res.getString(R.string.video_intro_subtitle),
            introModeHighlights = res.getString(R.string.video_intro_mode_highlights),
            introModeFull = res.getString(R.string.video_intro_mode_full),
            introSource = res.getString(R.string.video_intro_source),
            introDate = res.getString(R.string.video_intro_date),
            introDuration = res.getString(R.string.video_intro_duration),
            introDefects = res.getString(R.string.video_intro_defects),
            summaryTitle = res.getString(R.string.video_summary_title),
            summaryNote = res.getString(R.string.video_summary_note),
            captionTime = res.getString(R.string.video_caption_time),
            captionConfidence = res.getString(R.string.video_caption_confidence),
            captionSeverity = res.getString(R.string.video_caption_severity),
            fragment = res.getString(R.string.video_fragment),
            noDefects = res.getString(R.string.video_no_defects_card),
            noDefectsHint = res.getString(R.string.video_no_defects_hint),
            demo = res.getString(R.string.video_demo_mark),
            offlineNote = res.getString(R.string.video_offline_card),
            typeNames = mapOf(
                DefectType.POTHOLE to res.getString(R.string.type_pothole),
                DefectType.CRACK_NETWORK to res.getString(R.string.type_crack),
                DefectType.RUTTING to res.getString(R.string.type_rutting),
                DefectType.PATCH to res.getString(R.string.type_patch),
                DefectType.MANHOLE_COVER to res.getString(R.string.type_manhole),
                DefectType.UNKNOWN to res.getString(R.string.type_unknown),
            ),
        )
    }

    override fun onCleared() {
        job?.cancel()
        if (detectorLazy.isInitialized()) runCatching { detectorLazy.value.close() }
        deleteResult()
        super.onCleared()
    }
}
