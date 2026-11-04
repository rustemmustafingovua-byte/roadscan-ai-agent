package ai.roadscan.core.video

import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.geo.MonocularAreaEstimator
import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.DefectRecord
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Участок дороги, к которому привязывается видеообследование.
 *
 * В готовом видео нет ни GPS, ни акселерометра, поэтому положение дефекта
 * восстанавливается по километражу: оператор задаёт участок (дорога, км от–до),
 * а приложение раскладывает дефекты по участку пропорционально времени в ролике.
 * Если задана начальная точка и азимут, приложение строит и геометрию:
 * прямую ось участка с дефектами на своих километрах.
 */
data class RoadSection(
    /** Дорога: «М-06, км 45+200 — 49+900». */
    val roadName: String = "",
    /** Начало участка, км. */
    val kmFrom: Double = 0.0,
    /** Конец участка, км. */
    val kmTo: Double = 0.0,
    /** Начальная точка участка (широта), если известна. */
    val startLat: Double? = null,
    /** Начальная точка участка (долгота), если известна. */
    val startLon: Double? = null,
    /** Азимут оси участка от начала, градусы (0 — на север). */
    val bearingDeg: Double = 0.0,
) {
    val lengthKm: Double get() = (kmTo - kmFrom).coerceAtLeast(0.0)

    val hasGeometry: Boolean get() = startLat != null && startLon != null

    val isFilled: Boolean get() = roadName.isNotBlank() || kmTo > kmFrom

    companion object {
        val EMPTY = RoadSection()
    }
}

/**
 * Дефект, восстановленный из видео: один физический дефект, собранный из
 * десятков покадровых детекций.
 */
data class SurveyDefect(
    val id: String,
    val type: DefectType,
    /** Начало присутствия дефекта в ролике, мс. */
    val startMs: Long,
    /** Конец присутствия, мс. */
    val endMs: Long,
    /** Километраж по оси участка. */
    val km: Double,
    val confidence: Float,
    val box: BoundingBox,
    /** Сколько сэмплов видео видели этот дефект — доказательство устойчивости. */
    val frames: Int,
    /** Подтверждён устойчивым присутствием в кадрах (в видео нет акселерометра). */
    val confirmed: Boolean,
    val areaM2: Double,
    val depthMm: Double,
    val severity: Int,
    val lat: Double?,
    val lon: Double?,
) {
    /** Запись дефекта в общем формате — для кластеризации и сметы. */
    fun toRecord(sessionId: String): DefectRecord = DefectRecord(
        id = id,
        sessionId = sessionId,
        timestampMs = startMs,
        location = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
        type = type,
        severity = severity,
        confirmed = confirmed,
        status = if (confirmed) {
            ai.roadscan.core.model.ConfirmationStatus.CONFIRMED_PHYSICAL
        } else {
            ai.roadscan.core.model.ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY
        },
        estimatedAreaM2 = areaM2,
        estimatedDepthMm = depthMm,
        visionConfidence = confidence,
        impactG = 0f,
        nearField = box.bottom >= 0.55f,
    )
}

data class SurveyConfig(
    /** Разрыв между детекциями, при котором это ещё один дефект, мс. */
    val mergeGapMs: Long = 1_800,
    /** Минимальное число сэмплов, при котором дефект считается подтверждённым. */
    val minPersistentFrames: Int = 3,
    /** Минимальная уверенность детекции, попадающей в ведомость. */
    val minConfidence: Float = 0.35f,
    /** Принятая глубина дефекта, мм (в видео её не измерить). */
    val assumedDepthMm: Double = 10.0,
    /** Допустимое перекрытие рамок при склейке (IoU). */
    val mergeIou: Float = 0.25f,
) {
    companion object {
        val DEFAULT = SurveyConfig()
    }
}

/**
 * Конвейер «видео → ведомость дефектов».
 *
 * Покадровые детекции склеиваются в физические дефекты, каждому приписывается
 * километраж по участку и, если задана геометрия, координаты. Подтверждение
 * дефекта в видео — не акселерометр, а устойчивое присутствие в кадрах:
 * настоящая яма видна в ряде соседних сэмплов, а блик или тень — нет.
 */
object VideoSurveyPlanner {

    fun plan(
        analysis: VideoAnalysis,
        section: RoadSection = RoadSection.EMPTY,
        config: SurveyConfig = SurveyConfig.DEFAULT,
        calibration: ai.roadscan.core.calibration.CalibrationProfile =
            ai.roadscan.core.calibration.CalibrationProfile.DEFAULT,
        width: Int = analysis.width,
        height: Int = analysis.height,
        sessionId: String = "SES-video",
    ): List<SurveyDefect> {
        val duration = analysis.durationMs.coerceAtLeast(1L)
        val groups = groupDetections(analysis.detections, config)
        val agentConfig = calibration.applyTo()

        val estimator = MonocularAreaEstimator(agentConfig)
        val frame = ai.roadscan.core.model.FrameData(
            timestampMs = 0,
            location = null,
            cameraHeightM = calibration.cameraHeightM,
            cameraPitchDeg = calibration.cameraPitchDeg,
            imageWidth = width.coerceAtLeast(1),
            imageHeight = height.coerceAtLeast(1),
        )

        return groups.mapIndexed { index, group ->
            val peak = group.peak
            val ratio = (peak.timeMs.toDouble() / duration).coerceIn(0.0, 1.0)
            val km = section.kmFrom + section.lengthKm * ratio
            val position = positionAt(section, km - section.kmFrom)
            val area = estimator.estimate(
                ai.roadscan.core.model.VisionDetection(
                    type = peak.type,
                    confidence = peak.confidence,
                    box = peak.box,
                ),
                frame,
            ).areaM2
            val confirmed = group.frames >= config.minPersistentFrames
            SurveyDefect(
                id = "VID-${index.toString().padStart(3, '0')}",
                type = peak.type,
                startMs = group.startMs,
                endMs = group.endMs,
                km = km,
                confidence = peak.confidence,
                box = peak.box,
                frames = group.frames,
                confirmed = confirmed,
                areaM2 = area,
                depthMm = if (confirmed) config.assumedDepthMm else 0.0,
                severity = severityOf(peak.type, peak.confidence, area),
                lat = position?.first,
                lon = position?.second,
            )
        }.sortedBy { it.startMs }
    }

    /** Сводный результат обследования: дефекты + счётчики. */
    fun summarize(defects: List<SurveyDefect>): SurveySummary {
        val confirmed = defects.count { it.confirmed }
        return SurveySummary(
            total = defects.size,
            confirmed = confirmed,
            visualOnly = defects.size - confirmed,
            areaM2 = defects.filter { it.confirmed }.sumOf { it.areaM2 },
        )
    }

    private data class Group(
        val startMs: Long,
        val endMs: Long,
        val peak: ObservedDefect,
        val frames: Int,
    )

    /**
     * Склейка покадровых детекций: один тип + перекрытие рамок + близкое по
     * времени = один физический дефект.
     */
    private fun groupDetections(
        detections: List<ObservedDefect>,
        config: SurveyConfig,
    ): List<Group> {
        val sorted = detections
            .filter { it.confidence >= config.minConfidence }
            .sortedBy { it.timeMs }
        if (sorted.isEmpty()) return emptyList()

        val groups = mutableListOf<Group>()
        var current = sorted.first()
        var currentEnd = current.timeMs
        var currentFrames = 1

        fun flush() {
            groups += Group(current.startMs(), currentEnd, current, currentFrames)
        }

        for (detection in sorted.drop(1)) {
            val sameType = detection.type == current.type
            val contiguous = detection.timeMs - currentEnd <= config.mergeGapMs
            val overlaps = iou(current.box, detection.box) >= config.mergeIou
            if (sameType && contiguous && overlaps) {
                currentEnd = detection.timeMs
                currentFrames++
                if (detection.confidence > current.confidence) current = detection
            } else {
                flush()
                current = detection
                currentEnd = detection.timeMs
                currentFrames = 1
            }
        }
        flush()
        return groups
    }

    private fun ObservedDefect.startMs(): Long = timeMs

    /**
     * Положение точки участка: от начала по азимуту на расстояние (км от начала).
     */
    private fun positionAt(section: RoadSection, kmFromStart: Double): Pair<Double, Double>? {
        val lat = section.startLat ?: return null
        val lon = section.startLon ?: return null
        val meters = kmFromStart * 1000.0
        val bearing = Math.toRadians(section.bearingDeg)
        // Смещение на сфере: малое расстояние — прямолинейное приближение.
        val dLat = (meters * cos(bearing)) / 111_320.0
        val dLon = (meters * sin(bearing)) / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return (lat + dLat) to (lon + dLon)
    }

    /**
     * Критичность по видео: тип + площадь + уверенность.
     *
     * Уверенность заменяет вклад удара подвески — в видео физики нет,
     * поэтому доказательством служит сама устойчивость и сила детекции.
     */
    private fun severityOf(type: DefectType, confidence: Float, areaM2: Double): Int {
        val base = when (type) {
            DefectType.POTHOLE -> 2.0
            DefectType.CRACK_NETWORK -> 1.5
            DefectType.RUTTING -> 1.5
            DefectType.MANHOLE_COVER -> 1.0
            DefectType.PATCH -> 1.0
            DefectType.UNKNOWN -> 1.0
        }
        val areaPoints = when {
            areaM2 >= 5.0 -> 1.5
            areaM2 >= 1.5 -> 1.0
            areaM2 >= 0.4 -> 0.5
            else -> 0.0
        }
        val evidence = when {
            confidence >= 0.80f -> 1.5
            confidence >= 0.60f -> 1.0
            confidence >= 0.45f -> 0.5
            else -> 0.0
        }
        return (base + areaPoints + evidence).roundToInt().coerceIn(1, 5)
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        if (intersection <= 0f) return 0f
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}

/** Итоги видеообследования. */
data class SurveySummary(
    val total: Int = 0,
    val confirmed: Int = 0,
    val visualOnly: Int = 0,
    val areaM2: Double = 0.0,
)
