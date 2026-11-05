package ai.roadscan.app.video

import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.video.MontageEvent
import ai.roadscan.core.video.MontageMode
import ai.roadscan.core.video.MontagePlan
import ai.roadscan.core.video.MontageSegment
import ai.roadscan.core.video.ObservedDefect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тайминги готового ролика: нумерация находок и позиция превью.
 * Обе функции — чистая логика, но живут в app-модуле рядом с рендерером:
 * превью обязано перескакивать ровно туда, где видно дефект.
 */
class MontageTimingTest {

    private fun event(
        timeMs: Long,
        type: DefectType = DefectType.POTHOLE,
        startMs: Long,
        endMs: Long,
    ) = MontageEvent(
        startMs = startMs,
        endMs = endMs,
        peak = ObservedDefect(
            timeMs = timeMs,
            type = type,
            confidence = 0.9f,
            box = BoundingBox(0.1f, 0.1f, 0.5f, 0.5f),
        ),
        score = 1f,
    )

    private fun plan(vararg segments: MontageSegment) = MontagePlan(
        mode = MontageMode.HIGHLIGHTS,
        segments = segments.toList(),
        videoDurationMs = segments.sumOf { it.durationMs },
        eventCount = segments.sumOf { it.events.size },
    )

    @Test
    fun `нумерация идёт по времени появления, а не по порядку сегментов`() {
        // Второй сегмент содержит событие «раньше» первого по времени.
        val late = event(timeMs = 5_000, startMs = 4_000, endMs = 6_000)
        val early = event(timeMs = 1_000, startMs = 9_000, endMs = 11_000)
        val ids = VideoMontageRenderer.eventIds(
            plan(
                MontageSegment(4_000, 6_000, listOf(late)),
                MontageSegment(9_000, 11_000, listOf(early)),
            ),
        )
        assertEquals("P-001", ids[early])
        assertEquals("P-002", ids[late])
    }

    @Test
    fun `счётчики типов независимы — каждый тип с нуля`() {
        val hole = event(timeMs = 1_000, type = DefectType.POTHOLE, startMs = 0, endMs = 2_000)
        val crack = event(timeMs = 2_000, type = DefectType.CRACK_NETWORK, startMs = 0, endMs = 3_000)
        val hole2 = event(timeMs = 3_000, type = DefectType.POTHOLE, startMs = 0, endMs = 4_000)
        val ids = VideoMontageRenderer.eventIds(
            plan(MontageSegment(0, 4_000, listOf(hole, crack, hole2))),
        )
        assertEquals("P-001", ids[hole])
        assertEquals("C-001", ids[crack])
        assertEquals("P-002", ids[hole2])
    }

    @Test
    fun `таймкод первого сегмента — титул плюс смещение внутри`() {
        val found = event(timeMs = 3_500, startMs = 3_000, endMs = 4_000)
        val time = VideoMontageRenderer.outputTimeMs(
            plan(MontageSegment(3_000, 8_000, listOf(found))),
            found,
        )
        // 2600 мс титула + 500 мс от начала фрагмента до пика.
        assertEquals(VideoBrander.INTRO_MS + 500L, time)
    }

    @Test
    fun `таймкод второго сегмента — через длительность первого`() {
        val first = event(timeMs = 1_000, startMs = 0, endMs = 2_000)
        val second = event(timeMs = 6_500, startMs = 6_000, endMs = 9_000)
        val p = plan(
            MontageSegment(0, 5_000, listOf(first)),
            MontageSegment(6_000, 9_000, listOf(second)),
        )
        val time = VideoMontageRenderer.outputTimeMs(p, second)
        assertEquals(VideoBrander.INTRO_MS + 5_000L + 500L, time)
        // Первый сегмент считается от титула.
        assertEquals(VideoBrander.INTRO_MS + 1_000L, VideoMontageRenderer.outputTimeMs(p, first))
    }

    @Test
    fun `событие вне сегментов отдаёт начало ролика, а не мусор`() {
        val orphan = event(timeMs = 99_000, startMs = 98_000, endMs = 100_000)
        val p = plan(MontageSegment(0, 5_000, emptyList()))
        assertEquals(VideoBrander.INTRO_MS, VideoMontageRenderer.outputTimeMs(p, orphan))
        // Пустой план вовсе.
        assertEquals(
            VideoBrander.INTRO_MS,
            VideoMontageRenderer.outputTimeMs(plan(), orphan),
        )
    }

    @Test
    fun `смещение внутри сегмента не выходит за его границы`() {
        // Пик раньше начала фрагмента — зажимаем в 0.
        val tooEarly = event(timeMs = 500, startMs = 1_000, endMs = 3_000)
        assertEquals(
            VideoBrander.INTRO_MS,
            VideoMontageRenderer.outputTimeMs(
                plan(MontageSegment(1_000, 3_000, listOf(tooEarly))),
                tooEarly,
            ),
        )
        // Пик позже конца фрагмента — зажимаем в длительность.
        val tooLate = event(timeMs = 9_999, startMs = 1_000, endMs = 3_000)
        assertEquals(
            VideoBrander.INTRO_MS + 2_000L,
            VideoMontageRenderer.outputTimeMs(
                plan(MontageSegment(1_000, 3_000, listOf(tooLate))),
                tooLate,
            ),
        )
    }

    @Test
    fun `таймкоды монотонны — превью не прыгает назад по ролику`() {
        val a = event(timeMs = 1_000, startMs = 0, endMs = 2_000)
        val b = event(timeMs = 4_000, startMs = 3_000, endMs = 5_000)
        val c = event(timeMs = 8_000, startMs = 7_000, endMs = 9_000)
        val p = plan(
            MontageSegment(0, 2_000, listOf(a)),
            MontageSegment(3_000, 5_000, listOf(b)),
            MontageSegment(7_000, 9_000, listOf(c)),
        )
        val ta = VideoMontageRenderer.outputTimeMs(p, a)
        val tb = VideoMontageRenderer.outputTimeMs(p, b)
        val tc = VideoMontageRenderer.outputTimeMs(p, c)
        assertTrue(ta < tb && tb < tc)
        // И каждый таймкод внутри готового ролика (титул + все фрагменты).
        val total = VideoBrander.INTRO_MS + p.segments.sumOf { it.durationMs }
        assertTrue(tc < total)
    }
}
