package ai.roadscan.app.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.roadscan.app.RoadScanApp
import ai.roadscan.app.data.SessionEntity
import ai.roadscan.app.location.LocationCollector
import ai.roadscan.app.perception.FrameAnalyzer
import ai.roadscan.app.perception.SensorCollector
import ai.roadscan.app.perception.YoloV8Detector
import ai.roadscan.app.report.ExportManager
import ai.roadscan.app.report.ReportFormat
import ai.roadscan.app.telemetry.TelemetryReader
import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.agent.PostProcessingAgent
import ai.roadscan.core.agent.RoadScanAgent
import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.agent.SessionStats
import ai.roadscan.core.model.AgentEvent
import ai.roadscan.core.model.AgentState
import ai.roadscan.core.model.EnvironmentSnapshot
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
    val modelReady: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as RoadScanApp).container
    private val config: AgentConfig = container.config

    private val detector = YoloV8Detector(application)
    private val agent = RoadScanAgent(
        config = config,
        detector = detector,
        eventListener = { event -> onAgentEvent(event) },
    )
    private val postProcessing = PostProcessingAgent(config, container.priceCatalog)
    private val repository = container.repository
    private val exports = ExportManager(application)

    private val location = LocationCollector(application)
    private val telemetry = TelemetryReader(application, location)
    private val sensors = SensorCollector(application, agent)

    val analyzer = FrameAnalyzer(
        agent = agent,
        locationProvider = { location.latest },
        speedProvider = { location.speedKmh },
    )

    private val _state = MutableStateFlow(UiState(modelReady = detector.isReady))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var pendingShare: Intent? = null

    init {
        viewModelScope.launch {
            repository.observeSessions().collect { sessions ->
                _state.update { it.copy(sessions = sessions) }
            }
        }
    }

    fun startSession() {
        if (_state.value.running) return
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
        _state.update { it.copy(running = true, agentState = agent.state) }
        startTicker()
    }

    fun stopSession() {
        ticker?.cancel()
        ticker = null
        sensors.stop()
        location.stop()

        val data = agent.finishSession()
        if (data == null) {
            _state.update { it.copy(running = false, agentState = agent.state) }
            return
        }
        val report = postProcessing.process(data)
        agent.postProcessingDone()
        _state.update {
            it.copy(
                running = false,
                agentState = agent.state,
                report = report,
                confirmed = report.stats.confirmed,
                visualOnly = report.stats.visualOnly,
                events = (listOf("Смена завершена: ${report.clusters.size} кластеров, ${report.estimate.totalRub.toLong()} ₽") + it.events).take(8),
            )
        }
        viewModelScope.launch {
            runCatching { repository.save(report) }
        }
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
                _state.update {
                    it.copy(
                        agentState = agent.state,
                        targetFps = agent.lastGovernorDecision.targetFps,
                        speedKmh = snapshot.speedKmh,
                        batteryPercent = snapshot.batteryPercent,
                        cpuTempC = snapshot.cpuTempC,
                        gpsAccuracyM = location.accuracyM,
                        freeDiskMb = snapshot.freeDiskMb,
                        frames = agent.statsSnapshot().framesAnalyzed,
                        confirmed = defects.count { d -> d.confirmed },
                        visualOnly = defects.count { d -> !d.confirmed },
                    )
                }
                delay(1_000)
            }
        }
    }

    private fun onAgentEvent(event: AgentEvent) {
        val text = when (event) {
            is AgentEvent.VoicePrompt -> event.message
            is AgentEvent.LensDirty -> RoadScanAgent.LENS_WARNING
            is AgentEvent.Error -> event.message
            is AgentEvent.BatteryLow -> "Заряд батареи: ${event.percent}%"
            is AgentEvent.ThermalThrottled -> "Троттлинг: ${event.targetFps} FPS (${event.cpuTempC.toInt()}°C)"
            is AgentEvent.ThermalRecovered -> "Нагрев спал: ${event.targetFps} FPS"
            else -> null
        } ?: return
        _state.update { it.copy(events = (listOf(text) + it.events).take(8)) }
    }

    fun export(format: ReportFormat) {
        val report = _state.value.report ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = exports.export(report, format)
                    file to exports.shareIntent(file, format)
                }
            }
            _state.update { it.copy(busy = false) }
            result.onSuccess { (file, intent) ->
                pendingShare = intent
                _state.update { it.copy(message = "Готово: ${file.name}") }
                runCatching {
                    val chooser = Intent.createChooser(intent, "Отправить отчёт").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    getApplication<Application>().startActivity(chooser)
                }
            }.onFailure {
                _state.update { it.copy(message = "Ошибка экспорта: ${it.message}") }
            }
        }
    }

    fun openSession(session: SessionEntity) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val defects = withContext(Dispatchers.IO) { repository.loadDefects(session.id) }
            val report = postProcessing.process(
                sessionId = session.id,
                startedAtMs = session.startedAtMs,
                endedAtMs = session.endedAtMs,
                defects = defects,
                stats = SessionStats(
                    confirmed = session.confirmedCount,
                    visualOnly = session.visualOnlyCount,
                ),
            )
            _state.update { it.copy(report = report, busy = false) }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
        pendingShare = null
    }

    override fun onCleared() {
        ticker?.cancel()
        sensors.stop()
        location.stop()
        detector.close()
        super.onCleared()
    }
}
