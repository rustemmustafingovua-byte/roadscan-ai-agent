package ai.roadscan.core.perception

import ai.roadscan.core.agent.AgentConfig

/**
 * Адаптивный контроллер частоты анализа кадров.
 *
 * Правила ТЗ:
 *  - SoC > 42°C  -> 15 FPS (или каждый второй кадр);
 *  - SoC > 46°C  -> 6 FPS (аварийный режим, чтобы не словить троттлинг);
 *  - остывание ниже 40°C -> возврат к 30 FPS (гистерезис, чтобы не «дребезжать»);
 *  - заряд < 20% -> 15 FPS; < 10% -> 6 FPS.
 */
class AdaptiveFrameGovernor(
    private val config: AgentConfig = AgentConfig.DEFAULT,
) {
    enum class Mode { NORMAL, THERMAL_THROTTLE, EMERGENCY, BATTERY_SAVER }

    data class Decision(
        val targetFps: Int,
        val frameStride: Int,
        val mode: Mode,
        val cpuTempC: Float,
    )

    var decision: Decision = Decision(
        targetFps = config.maxAnalysisFps,
        frameStride = 1,
        mode = Mode.NORMAL,
        cpuTempC = Float.NaN,
    )
        private set

    private var frameCounter = 0L

    fun update(cpuTempC: Float, batteryPercent: Int, batteryTempC: Float = Float.NaN): Decision {
        val temp = listOf(cpuTempC, batteryTempC).filter { !it.isNaN() }.maxOrNull() ?: Float.NaN
        val next = when {
            temp >= config.socTempEmergencyC -> Decision(
                targetFps = config.emergencyFps,
                frameStride = strideFor(config.emergencyFps),
                mode = Mode.EMERGENCY,
                cpuTempC = temp,
            )

            batteryPercent <= config.batteryCriticalPercent -> Decision(
                targetFps = config.emergencyFps,
                frameStride = strideFor(config.emergencyFps),
                mode = Mode.EMERGENCY,
                cpuTempC = temp,
            )

            temp >= config.socTempThrottleC -> Decision(
                targetFps = config.throttledFps,
                frameStride = strideFor(config.throttledFps),
                mode = Mode.THERMAL_THROTTLE,
                cpuTempC = temp,
            )

            batteryPercent <= config.batteryLowPercent -> Decision(
                targetFps = config.throttledFps,
                frameStride = strideFor(config.throttledFps),
                mode = Mode.BATTERY_SAVER,
                cpuTempC = temp,
            )

            // гистерезис: из троттлинга выходим только после остывания
            decision.mode != Mode.NORMAL && temp > config.socTempRecoverC -> decision

            else -> Decision(
                targetFps = config.maxAnalysisFps,
                frameStride = 1,
                mode = Mode.NORMAL,
                cpuTempC = temp,
            )
        }

        if (next != decision) {
            decision = next
            frameCounter = 0
        }
        return decision
    }

    /** Нужно ли анализировать этот кадр (пропуск каждого N-го при троттлинге). */
    fun shouldAnalyzeNextFrame(): Boolean {
        val stride = decision.frameStride.coerceAtLeast(1)
        frameCounter++
        return stride == 1 || (frameCounter % stride) == 0L
    }

    fun reset() {
        frameCounter = 0
        decision = Decision(config.maxAnalysisFps, 1, Mode.NORMAL, Float.NaN)
    }

    private fun strideFor(targetFps: Int): Int =
        (config.maxAnalysisFps.toDouble() / targetFps).toInt().coerceAtLeast(1)
}
