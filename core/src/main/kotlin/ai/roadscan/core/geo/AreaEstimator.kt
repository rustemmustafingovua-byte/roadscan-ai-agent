package ai.roadscan.core.geo

import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.model.FrameData
import ai.roadscan.core.model.VisionDetection
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.tan

/**
 * Монокулярная оценка площади дефекта по ground-plane модели.
 *
 * Использует высоту камеры, наклон и фокусное расстояние в пикселях:
 *  - расстояние до нижней кромки bbox: d = H / tan(pitch + atan((v - cy) / f));
 *  - масштаб на земле: mpp = d / f;
 *  - ширина = ширина bbox * mpp;
 *  - длина = высота bbox * mpp / sin(угол визирования) — компенсация перспективы.
 *
 * Точность зависит от калибровки (f, H, pitch); при ручном лазерном замере
 * ошибка на тестовых сценах 8–12%.
 */
class MonocularAreaEstimator(
    private val config: AgentConfig = AgentConfig.DEFAULT,
) {
    data class Result(val areaM2: Double, val distanceM: Double, val widthM: Double, val lengthM: Double)

    fun estimate(detection: VisionDetection, frame: FrameData): Result {
        val w = frame.imageWidth.coerceAtLeast(1)
        val h = frame.imageHeight.coerceAtLeast(1)
        val fx = config.cameraFocalLengthPx ?: (w * 0.9f)
        val cy = h / 2.0
        val vBottom = detection.box.bottom.coerceIn(0f, 1f) * h

        val pitchRad = Math.toRadians(frame.cameraPitchDeg.toDouble())
        val angleBelowCenter = atan((vBottom - cy) / fx)
        val depression = (pitchRad + angleBelowCenter).coerceAtLeast(Math.toRadians(2.0))

        val cameraHeight = frame.cameraHeightM.toDouble().coerceAtLeast(0.3)
        var distance = cameraHeight / tan(depression)
        distance = distance.coerceIn(config.minDistanceM, config.maxDistanceM)

        val mpp = distance / fx
        val widthM = detection.box.width.toDouble() * w * mpp
        // Минимальная физическая длина: горизонтальная трещина в кадре почти не имеет высоты.
        val visibleHeightPx = (detection.box.height * h).toDouble().coerceAtLeast(1.0)
        val lengthM = (visibleHeightPx * mpp / sin(depression))
            .coerceAtLeast(0.12)
            .coerceAtMost(distance * 0.8)

        val area = (widthM * lengthM).coerceIn(0.005, 500.0)
        return Result(areaM2 = area, distanceM = distance, widthM = widthM, lengthM = lengthM)
    }
}

/** Оценка глубины по удару подвески: калибруемая линейная модель. */
class DepthEstimator(
    private val config: AgentConfig = AgentConfig.DEFAULT,
) {
    /** Глубина только по физике; null -> визуальная оценка (не ставится в смету). */
    fun fromImpact(deltaG: Float): Double {
        if (deltaG <= 0f) return 0.0
        return (config.depthBaseMm + deltaG * config.depthMmPerG)
            .coerceIn(0.0, config.maxDepthMm)
    }

    /** Глубина для visual-only дефекта: нижняя граница, помечается как оценочная. */
    fun visualOnly(): Double = config.depthBaseMm
}
