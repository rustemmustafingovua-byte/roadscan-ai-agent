package ai.roadscan.core.calibration

import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.i18n.CoreMessages
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Калибровочный профиль мобильного комплекса.
 *
 * Точность площади 10–15% достигается только после привязки геометрии к
 * конкретному автомобилю: высота камеры над покрытием, наклон вниз и фокусное
 * расстояние в пикселях. Профиль хранится на устройстве, применяется к
 * [AgentConfig] при старте смены и печатается в отчёте (аудит методики).
 */
@Serializable
data class CalibrationProfile(
    val name: String = "За замовчуванням",
    /** Высота оптического центра камеры над дорогой, м. */
    val cameraHeightM: Float = 1.3f,
    /** Наклон оптической оси вниз, градусы. */
    val cameraPitchDeg: Float = 8f,
    /** Фокусное расстояние в пикселях для ширины кадра анализа. */
    val focalLengthPx: Float? = null,
    val depthBaseMm: Double = 10.0,
    val depthMmPerG: Double = 25.0,
    /** Измеренная ширина колеи/объекта для самопроверки, м. */
    val referenceWidthM: Float? = null,
    /** Сколько метров до контрольного объекта при калибровке фокуса. */
    val referenceDistanceM: Float? = null,
    val vehicle: String = "",
    val calibratedAtMs: Long = 0,
) {
    /** Ошибки, которые блокируют коммерческую эксплуатацию профиля. */
    fun validationErrors(messages: CoreMessages = CoreMessages.DEFAULT): List<String> = buildList {
        if (cameraHeightM !in 0.4f..3.5f) add(messages.calibrationHeightError)
        if (cameraPitchDeg !in -5f..45f) add(messages.calibrationPitchError)
        val f = focalLengthPx
        if (f != null && f !in 200f..4000f) add(messages.calibrationFocalError)
        if (depthBaseMm < 0.0) add(messages.calibrationDepthBaseError)
        if (depthMmPerG <= 0.0) add(messages.calibrationDepthPerGError)
    }

    val isValid: Boolean get() = validationErrors().isEmpty()

    /** Профиль, готовый к полевым измерениям (фокус известен). */
    val hasOptics: Boolean get() = focalLengthPx != null && (focalLengthPx ?: 0f) > 0f

    fun applyTo(base: AgentConfig = AgentConfig.DEFAULT): AgentConfig = base.copy(
        cameraFocalLengthPx = focalLengthPx,
        depthBaseMm = depthBaseMm,
        depthMmPerG = depthMmPerG,
    )

    /** Ожидаемая ширина кадра на земле в метрах — для самопроверки перед выездом. */
    fun groundWidthAt(distanceM: Float): Float {
        val f = focalLengthPx ?: return Float.NaN
        return distanceM * (1f / f) * 1920f
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val DEFAULT = CalibrationProfile()

        /**
         * Фокусное расстояние по контрольному замеру:
         * f = w_px · Z / W, где W — известная ширина объекта (м), Z — дистанция (м).
         */
        fun estimateFocalLengthPx(knownWidthM: Float, distanceM: Float, widthPx: Float): Float? {
            if (knownWidthM <= 0f || distanceM <= 0f || widthPx <= 0f) return null
            return (widthPx * distanceM / knownWidthM).takeIf { it.isFinite() && it > 0f }
        }

        /** Обратная задача: какая ширина объекта должна быть в кадре при текущем фокусе. */
        fun expectedWidthPx(knownWidthM: Float, distanceM: Float, focalLengthPx: Float): Int =
            (knownWidthM * focalLengthPx / distanceM).roundToInt()

        fun fromJson(text: String): CalibrationProfile = runCatching {
            json.decodeFromString<CalibrationProfile>(text)
        }.getOrDefault(DEFAULT)

        fun toJson(profile: CalibrationProfile): String = json.encodeToString(profile)
    }
}
