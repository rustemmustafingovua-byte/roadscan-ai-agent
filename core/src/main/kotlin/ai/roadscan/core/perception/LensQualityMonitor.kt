package ai.roadscan.core.perception

import ai.roadscan.core.agent.AgentConfig

/**
 * Детектор помех на объективе.
 *
 * Признаки: нулевая/низкая контрастность кадра ИЛИ высокий «dirt pattern score»
 * (доля пикселей, похожих на капли/грязь — считается vision-слоем).
 * 10 кадров подряд -> предупреждение «Объектив загрязнен. Протрите камеру».
 */
class LensQualityMonitor(
    private val config: AgentConfig = AgentConfig.DEFAULT,
    private val onWarning: (consecutive: Int) -> Unit = {},
) {
    data class FrameQuality(
        val contrast: Float,
        val dirtPatternScore: Float = 0f,
    )

    var consecutiveBadFrames: Int = 0
        private set
    var warningActive: Boolean = false
        private set

    /** Возвращает true, если кадр считается «грязным». */
    fun onFrame(quality: FrameQuality): Boolean = onFrame(quality.contrast, quality.dirtPatternScore)

    fun onFrame(contrast: Float, dirtPatternScore: Float = 0f): Boolean {
        val bad = contrast < config.lensContrastThreshold || dirtPatternScore > 0.7f
        if (bad) {
            consecutiveBadFrames++
            if (!warningActive && consecutiveBadFrames >= config.lensDirtyFrames) {
                warningActive = true
                onWarning(consecutiveBadFrames)
            }
        } else {
            consecutiveBadFrames = 0
            warningActive = false
        }
        return bad
    }

    fun reset() {
        consecutiveBadFrames = 0
        warningActive = false
    }
}
