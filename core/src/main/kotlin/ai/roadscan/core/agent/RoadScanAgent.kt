package ai.roadscan.core.agent

import ai.roadscan.core.estimate.Estimate
import ai.roadscan.core.fusion.SensorFusionEngine
import ai.roadscan.core.geo.DefectCluster
import ai.roadscan.core.model.AgentEvent
import ai.roadscan.core.model.AgentState
import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectRecord
import ai.roadscan.core.model.EnvironmentSnapshot
import ai.roadscan.core.model.FrameData
import ai.roadscan.core.model.SensorPeak
import ai.roadscan.core.model.SensorSample
import ai.roadscan.core.model.StartResult
import ai.roadscan.core.model.VehicleTelemetry
import ai.roadscan.core.model.VisionDetection
import java.util.UUID

/** Порт vision-контура: TFLite YOLOv8 или тестовая заглушка. */
fun interface VisionDetector {
    fun detect(frame: FrameData): List<VisionDetection>
}

data class SessionStats(
    val framesSeen: Int = 0,
    val framesAnalyzed: Int = 0,
    val framesSkippedByThrottle: Int = 0,
    val rawDetections: Int = 0,
    val confirmed: Int = 0,
    val visualOnly: Int = 0,
    val duplicatesMerged: Int = 0,
    val lowConfidenceFiltered: Int = 0,
    val noGeoRejected: Int = 0,
    val peakCpuTempC: Float = Float.NaN,
    val averageLatencyMs: Double = 0.0,
) {
    val rejectedAsFalsePositive: Int get() = rawDetections - confirmed
}

data class SessionData(
    val sessionId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val defects: List<DefectRecord>,
    val stats: SessionStats,
)

data class SessionReport(
    val sessionId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val defects: List<DefectRecord>,
    val clusters: List<DefectCluster>,
    val visualOnlyClusters: List<DefectCluster>,
    val estimate: Estimate,
    val stats: SessionStats,
) {
    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0)
}

/**
 * Оркестратор RoadScan AI.
 *
 * Реализует Perceive-Reason-Act: принимает кадры и семплы акселерометра,
 * арбитрирует данные (sensor fusion), ведёт машину состояний и накапливает
 * подтверждённые дефекты. Пост-обработка и экспорт — в [PostProcessingAgent].
 */
class RoadScanAgent(
    val config: AgentConfig = AgentConfig.DEFAULT,
    private val detector: VisionDetector = VisionDetector { it.detections },
    private val clock: Clock = Clock.SYSTEM,
    private val eventListener: (AgentEvent) -> Unit = {},
) {
    var state: AgentState = AgentState.IDLE
        private set

    val fusion = SensorFusionEngine(config)
    val governor = ai.roadscan.core.perception.AdaptiveFrameGovernor(config)
    val lensMonitor = ai.roadscan.core.perception.LensQualityMonitor(config) { count ->
        emit(AgentEvent.LensDirty(count))
        emit(AgentEvent.VoicePrompt(LENS_WARNING, AgentEvent.VoicePrompt.Priority.HIGH))
    }

    private val areaEstimator = ai.roadscan.core.geo.MonocularAreaEstimator(config)
    private val depthEstimator = ai.roadscan.core.geo.DepthEstimator(config)
    private val severityScorer = ai.roadscan.core.fusion.SeverityScorer(config)

    private var sessionId: String? = null
    private var startedAtMs: Long = 0
    private val accepted = mutableListOf<DefectRecord>()
    private val recent = ArrayDeque<DefectRecord>()

    private var framesSeen = 0
    private var framesAnalyzed = 0
    private var framesSkipped = 0
    private var rawDetections = 0
    private var confirmedCount = 0
    private var visualOnlyCount = 0
    private var duplicatesMerged = 0
    private var lowConfidence = 0
    private var noGeo = 0
    private var peakTemp = Float.NaN
    private var latencySumMs = 0.0
    private var latencySamples = 0
    private var lastLowBatteryPromptAt = Long.MIN_VALUE
    private var lastGpsPromptAt = Long.MIN_VALUE
    private var lowBatteryWarned = false

    val currentSessionId: String? get() = sessionId
    val acceptedDefects: List<DefectRecord> get() = accepted.toList()
    val lastGovernorDecision get() = governor.decision

    fun addEventListener(listener: (AgentEvent) -> Unit) {
        listeners += listener
    }

    private val listeners = mutableListOf(eventListener)

    private fun emit(event: AgentEvent) {
        listeners.toList().forEach { it(event) }
    }

    private fun transition(to: AgentState) {
        val from = state
        if (from == to) return
        state = to
        emit(AgentEvent.StateChanged(from, to))
    }

    // ---------------------------------------------------------------- lifecycle

    fun startSession(environment: EnvironmentSnapshot): StartResult {
        val events = mutableListOf<AgentEvent>()
        val push = { e: AgentEvent -> events += e; emit(e) }

        if (environment.freeDiskMb < config.minFreeDiskMb) {
            transition(AgentState.ERROR)
            push(AgentEvent.Error("Недостаточно места на диске: ${environment.freeDiskMb} МБ. Освободите память и повторите."))
            return StartResult(false, state, events)
        }
        if (!environment.batteryTempC.isNaN() && environment.batteryTempC >= 45f) {
            transition(AgentState.ERROR)
            push(AgentEvent.Error("Батарея перегрета (${environment.batteryTempC.toInt()}°C). Дайте устройству остыть."))
            return StartResult(false, state, events)
        }

        sessionId = "SES-" + UUID.randomUUID().toString().take(8)
        startedAtMs = clock.nowMs()
        accepted.clear()
        recent.clear()
        fusion.clear()
        lensMonitor.reset()
        governor.reset()
        framesSeen = 0; framesAnalyzed = 0; framesSkipped = 0
        rawDetections = 0; confirmedCount = 0; visualOnlyCount = 0
        duplicatesMerged = 0; lowConfidence = 0; noGeo = 0
        peakTemp = Float.NaN; latencySumMs = 0.0; latencySamples = 0
        lowBatteryWarned = false
        lastGpsPromptAt = Long.MIN_VALUE

        transition(AgentState.CALIBRATING)

        if (!environment.cpuTempC.isNaN()) peakTemp = environment.cpuTempC

        val gpsOk = environment.gpsAccuracyM <= config.gpsAccuracyWarnM
        if (!gpsOk) {
            push(AgentEvent.VoicePrompt(GPS_WARNING, AgentEvent.VoicePrompt.Priority.HIGH))
        }
        if (environment.batteryPercent <= config.batteryLowPercent) {
            push(AgentEvent.BatteryLow(environment.batteryPercent))
            push(AgentEvent.VoicePrompt(BATTERY_WARNING, AgentEvent.VoicePrompt.Priority.HIGH))
            lowBatteryWarned = true
        }

        if (gpsOk) {
            transition(AgentState.SCANNING)
        }
        return StartResult(true, state, events)
    }

    /** Периодическое обновление условий; завершает калибровку, когда GPS в норме. */
    fun updateEnvironment(environment: EnvironmentSnapshot): AgentState {
        if (state == AgentState.CALIBRATING && environment.gpsAccuracyM <= config.gpsAccuracyWarnM) {
            transition(AgentState.SCANNING)
        }
        if (state == AgentState.SCANNING && environment.gpsAccuracyM > config.gpsAccuracyWarnM) {
            warnGpsThrottled()
        }
        return state
    }

    @Synchronized
    fun finishSession(): SessionData? {
        val id = sessionId ?: return null
        if (state == AgentState.IDLE || state == AgentState.ERROR) return null
        transition(AgentState.POST_PROCESSING)
        return SessionData(
            sessionId = id,
            startedAtMs = startedAtMs,
            endedAtMs = clock.nowMs(),
            defects = accepted.sortedBy { it.timestampMs },
            stats = snapshotStats(),
        )
    }

    fun statsSnapshot(): SessionStats = snapshotStats()

    fun postProcessingDone() {
        if (state == AgentState.POST_PROCESSING) transition(AgentState.IDLE)
    }

    fun abortSession() {
        sessionId = null
        accepted.clear()
        recent.clear()
        transition(AgentState.IDLE)
    }

    // ------------------------------------------------------------------ sensors

    fun onSensorSample(sample: SensorSample) = fusion.addSample(sample)

    fun onSensorSamples(samples: Iterable<SensorSample>) = fusion.addSamples(samples)

    fun onVehicleTelemetry(telemetry: VehicleTelemetry) {
        if (state != AgentState.SCANNING && state != AgentState.THROTTLED && state != AgentState.CALIBRATING) {
            return
        }

        if (!telemetry.cpuTempC.isNaN()) {
            peakTemp = if (peakTemp.isNaN()) telemetry.cpuTempC else maxOf(peakTemp, telemetry.cpuTempC)
        }

        val previous = governor.decision
        val decision = governor.update(
            cpuTempC = telemetry.cpuTempC,
            batteryPercent = telemetry.batteryPercent,
            batteryTempC = telemetry.batteryTempC,
        )

        if (decision.mode != previous.mode) {
            when (decision.mode) {
                ai.roadscan.core.perception.AdaptiveFrameGovernor.Mode.NORMAL -> {
                    if (state == AgentState.THROTTLED) {
                        transition(AgentState.SCANNING)
                        emit(AgentEvent.ThermalRecovered(decision.cpuTempC, decision.targetFps))
                    }
                }

                ai.roadscan.core.perception.AdaptiveFrameGovernor.Mode.THERMAL_THROTTLE -> {
                    if (state == AgentState.SCANNING) transition(AgentState.THROTTLED)
                    emit(AgentEvent.ThermalThrottled(decision.cpuTempC, decision.targetFps))
                    emit(
                        AgentEvent.VoicePrompt(
                            "Процессор нагрелся. Снижаю частоту анализа до ${decision.targetFps} кадров в секунду.",
                        ),
                    )
                }

                ai.roadscan.core.perception.AdaptiveFrameGovernor.Mode.EMERGENCY -> {
                    if (state == AgentState.SCANNING) transition(AgentState.THROTTLED)
                    emit(AgentEvent.ThermalThrottled(decision.cpuTempC, decision.targetFps))
                    emit(
                        AgentEvent.VoicePrompt(
                            "Критический нагрев. Перехожу в энергосберегающий режим.",
                            AgentEvent.VoicePrompt.Priority.HIGH,
                        ),
                    )
                }

                ai.roadscan.core.perception.AdaptiveFrameGovernor.Mode.BATTERY_SAVER -> Unit
            }
        }

        if (telemetry.batteryPercent <= config.batteryLowPercent && !lowBatteryWarned) {
            lowBatteryWarned = true
            lastLowBatteryPromptAt = clock.nowMs()
            emit(AgentEvent.BatteryLow(telemetry.batteryPercent))
            emit(
                AgentEvent.VoicePrompt(
                    BATTERY_WARNING,
                    AgentEvent.VoicePrompt.Priority.HIGH,
                ),
            )
        }

        if (telemetry.gpsAccuracyM > config.gpsAccuracyWarnM) warnGpsThrottled()

        if (telemetry.freeDiskMb < config.minFreeDiskMb) {
            emit(
                AgentEvent.VoicePrompt(
                    "Мало места на диске. Освободите ${config.minFreeDiskMb - telemetry.freeDiskMb} МБ.",
                    AgentEvent.VoicePrompt.Priority.HIGH,
                ),
            )
        }
    }

    private fun warnGpsThrottled() {
        val now = clock.nowMs()
        if (now - lastGpsPromptAt < 60_000) return
        lastGpsPromptAt = now
        emit(AgentEvent.VoicePrompt(GPS_WARNING, AgentEvent.VoicePrompt.Priority.HIGH))
    }

    // ------------------------------------------------------------ frame pipeline

    /**
     * Главный вход Perceive-Reason-Act. Возвращает запись, если дефект новый
     * (или upgraded visual-only -> confirmed), иначе null.
     */
    @Synchronized
    fun evaluateFrameAndSensor(
        frame: FrameData,
        sensorPeak: SensorPeak? = null,
    ): DefectRecord? {
        if (state != AgentState.SCANNING && state != AgentState.THROTTLED) return null

        framesSeen++
        lensMonitor.onFrame(frame.meanContrast)

        if (!governor.shouldAnalyzeNextFrame()) {
            framesSkipped++
            return null
        }

        val startedNs = System.nanoTime()
        val allDetections = frame.detections.ifEmpty { detector.detect(frame) }
        lowConfidence += allDetections.count { it.confidence < config.minVisionConfidenceCoerced }
        val detections = allDetections.filter { it.confidence >= config.minVisionConfidenceCoerced }
        val latencyMs = (System.nanoTime() - startedNs) / 1_000_000.0
        latencySumMs += latencyMs
        latencySamples++
        framesAnalyzed++

        if (detections.isEmpty()) return null

        rawDetections += detections.size
        val best = detections.maxWithOrNull(
            compareBy<VisionDetection> { if (it.type == ai.roadscan.core.model.DefectType.POTHOLE) 1 else 0 }
                .thenBy { it.confidence },
        ) ?: return null

        val location = frame.location
        if (location == null) {
            noGeo++
            return null
        }

        val peak = sensorPeak ?: fusion.peakAround(frame.sensorTimestampMs)
        val threshold = config.impactThresholdG(frame.speedKmh)
        val nearField = best.box.bottom >= (1f - config.nearFieldBandFraction)
        val confirmedByPhysics = peak != null && peak.deltaG > threshold && nearField

        val area = areaEstimator.estimate(best, frame)
        val depth = if (confirmedByPhysics) {
            depthEstimator.fromImpact(peak?.deltaG ?: 0f)
        } else {
            depthEstimator.visualOnly()
        }
        val severity = severityScorer.score(best, peak, area.areaM2, depth, threshold)

        val record = DefectRecord(
            id = "DEF-" + UUID.randomUUID().toString().take(8),
            sessionId = sessionId ?: "unknown",
            timestampMs = frame.timestampMs,
            location = location,
            type = best.type,
            severity = severity,
            confirmed = confirmedByPhysics,
            status = if (confirmedByPhysics) {
                ConfirmationStatus.CONFIRMED_PHYSICAL
            } else {
                ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY
            },
            estimatedAreaM2 = area.areaM2,
            estimatedDepthMm = depth,
            visionConfidence = best.confidence,
            impactG = peak?.deltaG ?: 0f,
            nearField = nearField,
        )

        val duplicate = findDuplicate(record)
        if (duplicate != null) {
            duplicatesMerged++
            if (record.confirmed && !duplicate.confirmed) {
                accepted.remove(duplicate)
                recent.remove(duplicate)
                accepted += record
                recent.addLast(record)
                confirmedCount++
                visualOnlyCount = (visualOnlyCount - 1).coerceAtLeast(0)
                emit(AgentEvent.DefectAccepted(record))
                return record
            }
            return null
        }

        if (record.confirmed) confirmedCount++ else visualOnlyCount++
        accepted += record
        recent.addLast(record)
        while (recent.size > 256) recent.removeFirst()
        emit(AgentEvent.DefectAccepted(record))
        return record
    }

    private fun findDuplicate(record: DefectRecord): DefectRecord? {
        val location = record.location ?: return null
        return recent.firstOrNull { candidate ->
            candidate.type == record.type &&
                kotlin.math.abs(candidate.timestampMs - record.timestampMs) <= config.dedupWindowMs &&
                candidate.location?.let {
                    ai.roadscan.core.geo.GeoMath.distanceMeters(it, location) <= config.dedupRadiusM
                } == true
        }
    }

    private fun snapshotStats(): SessionStats = SessionStats(
        framesSeen = framesSeen,
        framesAnalyzed = framesAnalyzed,
        framesSkippedByThrottle = framesSkipped,
        rawDetections = rawDetections,
        confirmed = confirmedCount,
        visualOnly = visualOnlyCount,
        duplicatesMerged = duplicatesMerged,
        lowConfidenceFiltered = lowConfidence,
        noGeoRejected = noGeo,
        peakCpuTempC = peakTemp,
        averageLatencyMs = if (latencySamples == 0) 0.0 else latencySumMs / latencySamples,
    )

    companion object {
        const val GPS_WARNING = "Внимание, низкая точность GPS. Рекомендуется подключить внешний RTK-модуль."
        const val BATTERY_WARNING =
            "Заряд батареи ниже 20%. Рекомендуется сохранить путевой лог и завершить смену или перейти в фоновый режим."
        const val LENS_WARNING = "Объектив загрязнен. Протрите камеру."
    }
}
