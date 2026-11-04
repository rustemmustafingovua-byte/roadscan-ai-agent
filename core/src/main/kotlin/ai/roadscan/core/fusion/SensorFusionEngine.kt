package ai.roadscan.core.fusion

import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.model.SensorPeak
import ai.roadscan.core.model.SensorSample
import kotlin.math.abs

/**
 * Кольцевой буфер акселерометра + поиск пика удара в окне ±windowMs вокруг кадра.
 *
 * Буфер ограничен по времени (2 секунды), чтобы не держать память при 200 Гц.
 *
 * Величина удара — отклонение модуля ускорения от 1 g ([SensorSample.deltaG]).
 * Модуль не зависит от ориентации телефона в салоне, поэтому одинаково
 * работает и на «плашмя», и на лобовом стекле, и вверх ногами; вдобавок
 * поперечные перегрузки (торможение, поворот) почти не меняют модуль и
 * отсекаются порогом.
 */
class SensorFusionEngine(
    private val config: AgentConfig = AgentConfig.DEFAULT,
    private val bufferWindowMs: Long = 2_000,
) {
    private val samples = ArrayDeque<SensorSample>()

    val size: Int get() = samples.size

    @Synchronized
    fun addSample(sample: SensorSample) {
        samples.addLast(sample)
        val cutoff = sample.timestampMs - bufferWindowMs
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            samples.removeFirst()
        }
    }

    @Synchronized
    fun addSamples(newSamples: Iterable<SensorSample>) {
        newSamples.forEach { addSample(it) }
    }

    /**
     * Ищет максимальный удар в окне [frameTimestampMs - windowMs, + windowMs].
     * Возвращает null, если в буфере нет данных (например, датчик ещё не прогрелся).
     */
    fun peakAround(
        frameTimestampMs: Long,
        windowMs: Long = config.sensorWindowMs,
    ): SensorPeak? {
        val window = windowSamples(frameTimestampMs, windowMs)
        if (window.isEmpty()) return null

        val peak = window.maxBy { it.deltaG }
        val half = peak.deltaG * 0.5f
        val aboveHalf = window.filter { it.deltaG >= half }
        val dt = medianIntervalMs(window)

        return SensorPeak(
            timestampMs = peak.timestampMs,
            deltaG = peak.deltaG,
            durationMs = (aboveHalf.size * dt).toLong(),
        )
    }

    @Synchronized
    fun windowSamples(frameTimestampMs: Long, windowMs: Long = config.sensorWindowMs): List<SensorSample> {
        val from = frameTimestampMs - windowMs
        val to = frameTimestampMs + windowMs
        return samples.filter { it.timestampMs in from..to }
    }

    @Synchronized
    fun clear() = samples.clear()

    private fun medianIntervalMs(window: List<SensorSample>): Double {
        if (window.size < 2) return 20.0
        val deltas = window.zipWithNext { a, b -> abs(b.timestampMs - a.timestampMs) }
            .filter { it > 0 }
            .sorted()
        if (deltas.isEmpty()) return 20.0
        return deltas[deltas.size / 2].toDouble()
    }
}
