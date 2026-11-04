package ai.roadscan.core.video

import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.DefectType

/**
 * Один распознанный дефект в сыром видео: момент времени и рамка в долях кадра.
 * Координаты нормализованы (0..1), поэтому не зависят от разрешения исходника.
 */
data class ObservedDefect(
    val timeMs: Long,
    val type: DefectType,
    val confidence: Float,
    val box: BoundingBox,
)

/** Результат прохода нейросети по видео (без GPS и акселерометра). */
data class VideoAnalysis(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sampledFps: Float,
    val detections: List<ObservedDefect>,
    /**
     * Снимки находок: момент времени в ролике -> путь к файлу.
     *
     * Снимок берётся из кадра, который уже разобран при анализе: отдельный
     * проход по ролику ради картинки стоил бы десятков секунд.
     */
    val photos: Map<Long, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = detections.isEmpty()
}

/**
 * Событие-дефект после склейки покадровых детекций: один физический дефект,
 * который виден несколько секунд, не должен превращаться в десятки роликов.
 */
data class MontageEvent(
    /** Начало окна показа рамки (для полного видео). */
    val startMs: Long,
    val endMs: Long,
    /** Кадр с максимальной уверенностью — по нему строится рамка и подпись. */
    val peak: ObservedDefect,
    val score: Float,
) {
    val type: DefectType get() = peak.type
    val confidence: Float get() = peak.confidence
}

/** Непрерывный фрагмент ролика. В режиме обзора — один дефект, в полном — весь файл. */
data class MontageSegment(
    val startMs: Long,
    val endMs: Long,
    val events: List<MontageEvent>,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    val score: Float get() = events.maxOfOrNull { it.score } ?: 0f
}

enum class MontageMode {
    /** Короткий ролик: только моменты с дефектами, титул и сводка. */
    HIGHLIGHTS,

    /** Всё видео с рамками и счётчиками (ограничено по длительности). */
    FULL,
}

data class MontageConfig(
    val preRollMs: Long = 1_500,
    val postRollMs: Long = 2_500,
    /** Покадровые детекции одного типа ближе этого интервала — один дефект. */
    val mergeGapMs: Long = 1_800,
    /** Окно показа рамки события в полном видео. */
    val eventWindowMs: Long = 800,
    val maxSegments: Int = 12,
    val maxHighlightDurationMs: Long = 75_000,
    val maxFullDurationMs: Long = 5 * 60_000,
) {
    companion object {
        val DEFAULT = MontageConfig()
    }
}

data class MontagePlan(
    val mode: MontageMode,
    val segments: List<MontageSegment>,
    /** Длительность видеоряда без титула и финальной сводки. */
    val videoDurationMs: Long,
    val eventCount: Int,
) {
    val isEmpty: Boolean get() = segments.isEmpty() || videoDurationMs <= 0
}

/**
 * Планировщик монтажа: из сырых детекций делает либо короткий обзор, либо
 * полный размеченный ролик. Чистая логика без Android — покрыта тестами.
 */
object MontagePlanner {

    private val typeWeight = mapOf(
        DefectType.POTHOLE to 1.0f,
        DefectType.RUTTING to 0.9f,
        DefectType.CRACK_NETWORK to 0.78f,
        DefectType.PATCH to 0.55f,
        DefectType.MANHOLE_COVER to 0.45f,
        DefectType.UNKNOWN to 0.3f,
    )

    fun plan(
        analysis: VideoAnalysis,
        mode: MontageMode,
        config: MontageConfig = MontageConfig.DEFAULT,
    ): MontagePlan {
        val duration = analysis.durationMs.coerceAtLeast(0)
        if (duration == 0L) return MontagePlan(mode, emptyList(), 0, 0)

        val events = events(analysis, config)
        if (mode == MontageMode.FULL) {
            val end = minOf(duration, config.maxFullDurationMs)
            val visible = events
                .filter { it.peak.timeMs < end }
                .map { it.copy(endMs = minOf(it.endMs, end)) }
            val segment = MontageSegment(0, end, visible)
            return MontagePlan(mode, listOf(segment), end, visible.size)
        }

        val candidates = events
            .map { event ->
                val start = (event.peak.timeMs - config.preRollMs).coerceAtLeast(0)
                val end = (event.peak.timeMs + config.postRollMs).coerceAtMost(duration)
                MontageSegment(start, end, listOf(event))
            }
            .filter { it.durationMs > 0 }
            .sortedByDescending { it.score }

        val picked = mutableListOf<MontageSegment>()
        var total = 0L
        for (candidate in candidates) {
            if (picked.size >= config.maxSegments) break
            if (total + candidate.durationMs > config.maxHighlightDurationMs) continue
            // Тот же дефект, попавший в соседний кадр, не дублируем в монтаже.
            if (picked.any { overlaps(it, candidate) }) continue
            picked += candidate
            total += candidate.durationMs
        }

        // Монтаж идёт по хронологии, а не по рейтингу — так ролик выглядит связно.
        val ordered = picked.sortedBy { it.startMs }
        return MontagePlan(
            mode = mode,
            segments = ordered,
            videoDurationMs = ordered.sumOf { it.durationMs },
            eventCount = ordered.sumOf { it.events.size },
        )
    }

    /**
     * Склейка покадровых детекций в события. Детекции одного типа с разрывом
     * меньше [MontageConfig.mergeGapMs] считаются одним дефектом; пиком
     * остаётся кадр с максимальной уверенностью.
     */
    fun events(
        analysis: VideoAnalysis,
        config: MontageConfig = MontageConfig.DEFAULT,
    ): List<MontageEvent> {
        val sorted = analysis.detections
            .filter { it.confidence > 0f }
            .sortedBy { it.timeMs }
        if (sorted.isEmpty()) return emptyList()

        val result = mutableListOf<MontageEvent>()
        var current = sorted.first()
        var currentEnd = current.timeMs

        fun flush() {
            val weight = typeWeight[current.type] ?: 0.5f
            result += MontageEvent(
                startMs = (current.timeMs - config.eventWindowMs).coerceAtLeast(0),
                endMs = currentEnd + config.eventWindowMs,
                peak = current,
                score = current.confidence * weight,
            )
        }

        for (detection in sorted.drop(1)) {
            val sameType = detection.type == current.type
            val contiguous = detection.timeMs - currentEnd <= config.mergeGapMs
            if (sameType && contiguous) {
                currentEnd = detection.timeMs
                if (detection.confidence > current.confidence) current = detection
            } else {
                flush()
                current = detection
                currentEnd = detection.timeMs
            }
        }
        flush()

        // Кадры одного дефекта могут давать несколько событий с почти равным
        // пиком — оставляем сильнейшее в пределах 0.5 с.
        return result
            .sortedByDescending { it.score }
            .fold(mutableListOf<MontageEvent>()) { acc, event ->
                val duplicate = acc.any {
                    it.type == event.type && kotlin.math.abs(it.peak.timeMs - event.peak.timeMs) < 500
                }
                if (!duplicate) acc += event
                acc
            }
            .sortedBy { it.peak.timeMs }
    }

    private fun overlaps(a: MontageSegment, b: MontageSegment): Boolean =
        a.startMs < b.endMs && b.startMs < a.endMs
}
