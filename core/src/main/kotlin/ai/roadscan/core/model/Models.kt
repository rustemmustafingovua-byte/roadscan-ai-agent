package ai.roadscan.core.model

/** Тип дорожного дефекта, который умеет распознавать vision-контур. */
enum class DefectType {
    POTHOLE,
    CRACK_NETWORK,
    RUTTING,
    PATCH,
    MANHOLE_COVER,
    UNKNOWN,
}

/**
 * Статус подтверждения дефекта.
 *
 * CONFIRMED_PHYSICAL — камера увидела дефект и акселерометр зафиксировал удар
 * в окне ±[SensorFusionEngine] мс -> попадает в смету.
 *
 * UNCONFIRMED_VISUAL_ONLY — камера увидела, физика не подтвердила (тень, блик,
 * объезд, лужа) -> сохраняется для аналитики, но не считается критическим ударом.
 */
enum class ConfirmationStatus {
    CONFIRMED_PHYSICAL,
    UNCONFIRMED_VISUAL_ONLY,
}

/** Состояние агента (машина состояний из ТЗ, п.4). */
enum class AgentState {
    IDLE,
    CALIBRATING,
    SCANNING,
    THROTTLED,
    POST_PROCESSING,
    ERROR,
}

/** Географическая точка. */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
    /** Горизонтальная точность в метрах (68%). NaN — неизвестна. */
    val accuracyM: Float = Float.NaN,
)

/** Нормализованный bounding box в долях кадра: 0..1. */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val areaFraction: Float get() = width * height
}

data class VisionDetection(
    val type: DefectType,
    val confidence: Float,
    val box: BoundingBox,
)

/**
 * Кадр, подготовленный Perception Engine.
 *
 * [imageRef] — непрозрачная ссылка на изображение (Bitmap/ImageProxy) для
 * конкретной реализации [VisionDetector]; ядро агента её не интерпретирует.
 */
data class FrameData(
    val timestampMs: Long,
    val location: GeoPoint?,
    val cameraHeightM: Float,
    val cameraPitchDeg: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val speedKmh: Float = 0f,
    /** Средний контраст кадра 0..1 (для детекции загрязнения линзы). */
    val meanContrast: Float = 1f,
    /** Метка кадра в шкале датчиков (elapsedRealtime, мс); по умолчанию == timestampMs. */
    val sensorTimestampMs: Long = timestampMs,
    val imageRef: Any? = null,
    val detections: List<VisionDetection> = emptyList(),
)

/** Один отсчёт акселерометра. zGForce — модуль ускорения в g (покой ≈ 1.0). */
data class SensorSample(
    val timestampMs: Long,
    val zGForce: Float,
    val xGForce: Float = 0f,
    val yGForce: Float = 0f,
) {
    val deltaG: Float get() = kotlin.math.abs(zGForce - 1f)
}

/** Пик удара подвески в окне поиска. */
data class SensorPeak(
    val timestampMs: Long,
    val deltaG: Float,
    val durationMs: Long = 0L,
) {
    val zGForce: Float get() = deltaG + 1f
}

/** Снимок условий на старте смены. */
data class EnvironmentSnapshot(
    val gpsAccuracyM: Float,
    val freeDiskMb: Long,
    val batteryPercent: Int,
    val batteryTempC: Float,
    val cpuTempC: Float = Float.NaN,
)

/** Телеметрия, которую Action Engine получает от системы в процессе смены. */
data class VehicleTelemetry(
    val timestampMs: Long,
    val speedKmh: Float = 0f,
    val gpsAccuracyM: Float = 0f,
    val cpuTempC: Float = Float.NaN,
    val batteryPercent: Int = 100,
    val batteryTempC: Float = 30f,
    val charging: Boolean = false,
    val freeDiskMb: Long = Long.MAX_VALUE,
)

/**
 * Запись о дефекте — единица обмена между агентом, Room DB и аналитикой.
 */
data class DefectRecord(
    val id: String,
    val sessionId: String,
    val timestampMs: Long,
    val location: GeoPoint?,
    val type: DefectType,
    /** Критичность 1..5 (5 — максимальная). */
    val severity: Int,
    val confirmed: Boolean,
    val status: ConfirmationStatus,
    val estimatedAreaM2: Double,
    val estimatedDepthMm: Double,
    val visionConfidence: Float,
    val impactG: Float,
    val nearField: Boolean,
    val latitude: Double? = location?.lat,
    val longitude: Double? = location?.lon,
)

/** События, которые агент отдаёт UI/голосовому движку. */
sealed interface AgentEvent {
    data class StateChanged(val from: AgentState, val to: AgentState) : AgentEvent

    data class VoicePrompt(
        val message: String,
        val priority: Priority = Priority.NORMAL,
    ) : AgentEvent {
        enum class Priority { NORMAL, HIGH, CRITICAL }
    }

    data class LensDirty(val consecutiveLowContrastFrames: Int) : AgentEvent

    data class ThermalThrottled(val cpuTempC: Float, val targetFps: Int) : AgentEvent

    data class ThermalRecovered(val cpuTempC: Float, val targetFps: Int) : AgentEvent

    data class BatteryLow(val percent: Int) : AgentEvent

    data class DefectAccepted(val record: DefectRecord) : AgentEvent

    data class Error(val message: String) : AgentEvent
}

/** Результат попытки старта смены. */
data class StartResult(
    val started: Boolean,
    val state: AgentState,
    val events: List<AgentEvent>,
)
