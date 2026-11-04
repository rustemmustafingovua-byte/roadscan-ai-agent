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

/**
 * Один отсчёт акселерометра в шкале g.
 *
 * Внимание: Android `TYPE_ACCELEROMETER` отдаёт м/с², а не g. Отсчёты,
 * приходящие из сенсорного контура, обязаны быть нормализованы через
 * [SensorSample.fromMetersPerSecondSquared] — иначе «покой» даёт 9.8 вместо
 * 1.0, [deltaG] всегда ≈ 8.8, и фузия подтверждает любой визуальный кандидат
 * как твёрдый удар.
 */
data class SensorSample(
    val timestampMs: Long,
    val zGForce: Float,
    val xGForce: Float = 0f,
    val yGForce: Float = 0f,
) {
    /**
     * Модуль ускорения, g. В покое ≈ 1.0 при любой ориентации телефона.
     */
    val magnitudeG: Float
        get() = kotlin.math.sqrt(
            xGForce * xGForce + yGForce * yGForce + zGForce * zGForce,
        )

    /**
     * Отклонение модуля ускорения от 1 g — величина удара.
     *
     * Считается по модулю, а не по одной оси Z: крепление телефона на лобовом
     * стекле не совпадает с осью Z, и «Δa_z» в такой установке не отражает
     * удар. Модуль, наоборот, инвариантен к ориентации:
     *
     *  - удар колеса о яму направлен вдоль силы тяжести -> модуль растёт,
     *    отклонение ≈ величине удара;
     *  - торможение и поворот направлены поперёк тяжести -> модуль почти не
     *    меняется (0.4 g вбок даёт всего ~0.08 g отклонения), поэтому
     *    модуль сам по себе отсекает ложные срабатывания на переходах.
     */
    val deltaG: Float get() = kotlin.math.abs(magnitudeG - 1f)

    companion object {
        /**
         * Стандартное ускорение свободного падения, м/с².
         * Совпадает с `android.hardware.SensorManager.GRAVITY_EARTH`, но живёт
         * в ядре, чтобы не тянуть Android-зависимости и покрыть тестами.
         */
        const val STANDARD_GRAVITY_MPS2 = 9.80665f

        /**
         * Перевод отсчёта Android-акселерометра (м/с²) в шкалу ядра (g).
         *
         * @param timestampMs отметка времени в шкале elapsedRealtime, мс
         * @param xMps2 ускорение по X, м/с² (покой ≈ 0)
         * @param yMps2 ускорение по Y, м/с² (покой ≈ 0)
         * @param zMps2 ускорение по Z, м/с² (покой ≈ 9.81)
         */
        fun fromMetersPerSecondSquared(
            timestampMs: Long,
            xMps2: Float,
            yMps2: Float,
            zMps2: Float,
        ): SensorSample = SensorSample(
            timestampMs = timestampMs,
            xGForce = xMps2 / STANDARD_GRAVITY_MPS2,
            yGForce = yMps2 / STANDARD_GRAVITY_MPS2,
            zGForce = zMps2 / STANDARD_GRAVITY_MPS2,
        )
    }
}

/** Пик удара подвески в окне поиска. */
data class SensorPeak(
    val timestampMs: Long,
    /** Отклонение модуля ускорения от 1 g — величина удара. */
    val deltaG: Float,
    val durationMs: Long = 0L,
) {
    /** Модуль ускорения в момент пика, g. */
    val magnitudeG: Float get() = deltaG + 1f
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
/**
 * Решение оператора по находке.
 *
 * Автоматика ошибается в обе стороны: принимает тень за яму (ложное
 * срабатывание) или промолкает о настоящей яме (пропуск). Оператор, который
 * видел дорогу, снимает оба риска: его решение — последнее.
 */
enum class ReviewDecision {
    /** Не смотрели. */
    UNREVIEWED,

    /** Оператор подтвердил: дефект настоящий. */
    CONFIRMED,

    /** Оператор отклонил: ложное срабатывание. */
    REJECTED,
}

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
    /**
     * Километраж по оси обследуемой дороги.
     *
     * В готовом видео GPS нет, поэтому положение дефекта задаётся километражом
     * участка. Если участок не привязан к точке на местности, координат у
     * дефекта не будет — но километраж останется, и он попадёт в ведомость.
     */
    val km: Double? = null,
    /** Решение оператора по находке. */
    val review: ReviewDecision = ReviewDecision.UNREVIEWED,
) {
    /**
     * Попадает ли дефект в смету.
     *
     * Автоматическое подтверждение (удар подвески) или подтверждение
     * оператором — и ни разу не отклонённый человеком.
     */
    val effectiveConfirmed: Boolean
        get() = review != ReviewDecision.REJECTED && (confirmed || review == ReviewDecision.CONFIRMED)
}

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

    data class BatteryLow(val percent: Int, val critical: Boolean = false) : AgentEvent

    data class DefectAccepted(val record: DefectRecord) : AgentEvent

    /** Vision-контур включён/выключен (фоновый режим при низком заряде). */
    data class VisionModeChanged(val enabled: Boolean) : AgentEvent

    data class Error(val message: String) : AgentEvent
}

/** Результат попытки старта смены. */
data class StartResult(
    val started: Boolean,
    val state: AgentState,
    val events: List<AgentEvent>,
)
