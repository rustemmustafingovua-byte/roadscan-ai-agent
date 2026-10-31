package ai.roadscan.core.agent

import ai.roadscan.core.model.GeoPoint
import ai.roadscan.core.model.SensorPeak

/**
 * Настройки агента. Значения по умолчанию подобраны под смартфон среднего
 * класса с камерой на лобовом стекле (высота ~1.3 м, наклон ~10° вниз).
 */
data class AgentConfig(
    // --- производительность / нагрев ---
    val maxAnalysisFps: Int = 30,
    val throttledFps: Int = 15,
    val emergencyFps: Int = 6,
    val socTempThrottleC: Float = 42f,
    val socTempRecoverC: Float = 40f,
    val socTempEmergencyC: Float = 46f,

    // --- питание ---
    val batteryLowPercent: Int = 20,
    val batteryCriticalPercent: Int = 10,

    // --- предусловия старта ---
    val minFreeDiskMb: Long = 500,
    val gpsAccuracyGoodM: Float = 8f,
    val gpsAccuracyWarnM: Float = 15f,

    // --- sensor fusion ---
    val sensorWindowMs: Long = 50,
    val nearFieldBandFraction: Float = 0.45f,
    val baseImpactThresholdG: Float = 0.28f,
    val speedThresholdPerKmh: Float = 0.004f,
    val maxImpactThresholdG: Float = 0.75f,
    val minVisionConfidence: Float = 0.35f,

    // --- качество линзы ---
    val lensContrastThreshold: Float = 0.12f,
    val lensDirtyFrames: Int = 10,

    // --- дедупликация / кластеризация ---
    val dedupRadiusM: Double = 4.0,
    val dedupWindowMs: Long = 6_000,
    val clusterEpsM: Double = 2.5,
    val clusterMinPoints: Int = 1,

    // --- смета ---
    val asphaltDensityTPerM3: Double = 2.4,
    val compactionFactor: Double = 1.25,
    val millingAllowanceMm: Double = 30.0,
    val depthBaseMm: Double = 10.0,
    val depthMmPerG: Double = 25.0,
    val maxDepthMm: Double = 150.0,

    // --- монокулярная геометрия ---
    /** Фокусное расстояние в пикселях; null -> оценка от ширины кадра. */
    val cameraFocalLengthPx: Float? = null,
    val minDistanceM: Double = 1.5,
    val maxDistanceM: Double = 60.0,
) {
    /** Динамический порог удара: с ростом скорости растёт шум подвески. */
    fun impactThresholdG(speedKmh: Float): Float =
        (baseImpactThresholdG + speedThresholdPerKmh * speedKmh.coerceAtLeast(0f))
            .coerceIn(baseImpactThresholdG, maxImpactThresholdG)

    val minVisionConfidenceCoerced: Float get() = minVisionConfidence.coerceIn(0f, 1f)

    companion object {
        val DEFAULT = AgentConfig()
    }
}

/** Источник геопозиции, отвязанный от Android Location API. */
fun interface LocationProvider {
    fun current(): GeoPoint?
}

/** Часы, чтобы тесты были детерминированными. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** Пик удара, найденный в буфере; null если физика молчит. */
typealias PeakProvider = (frameTimestampMs: Long, windowMs: Long) -> SensorPeak?
