package ai.roadscan.core.fusion

import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.SensorPeak
import ai.roadscan.core.model.VisionDetection
import kotlin.math.roundToInt

/**
 * Severity Scoring 1..5.
 *
 * Базовая критичность типа дефекта + вклад площади (лог. шкала) + вклад глубины
 * (из удара подвески) + бонус за удар существенно выше порога.
 */
class SeverityScorer(
    private val config: AgentConfig = AgentConfig.DEFAULT,
) {
    fun score(
        detection: VisionDetection,
        sensor: SensorPeak?,
        areaM2: Double,
        depthMm: Double,
        impactThresholdG: Float = config.impactThresholdG(0f),
    ): Int {
        val base = when (detection.type) {
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

        val depthPoints = when {
            depthMm >= 80.0 -> 1.5
            depthMm >= 45.0 -> 1.0
            depthMm >= 20.0 -> 0.5
            else -> 0.0
        }

        val impactPoints = if (sensor == null) {
            0.0
        } else {
            val ratio = sensor.deltaG / impactThresholdG.coerceAtLeast(0.01f)
            when {
                ratio >= 2.0 -> 1.0
                ratio >= 1.5 -> 0.5
                ratio >= 1.0 -> 0.25
                else -> 0.0
            }
        }

        // Низкая уверенность сети снижает критичность визуального кандидата.
        val confidenceAdjust = if (detection.confidence < 0.5f) -0.5 else 0.0

        return (base + areaPoints + depthPoints + impactPoints + confidenceAdjust)
            .roundToInt()
            .coerceIn(1, 5)
    }
}
