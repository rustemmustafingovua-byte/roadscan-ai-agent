package ai.roadscan.core.video

import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.DefectType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MontagePlannerTest {

    private fun detection(
        timeMs: Long,
        type: DefectType = DefectType.POTHOLE,
        confidence: Float = 0.8f,
    ) = ObservedDefect(
        timeMs = timeMs,
        type = type,
        confidence = confidence,
        box = BoundingBox(0.3f, 0.5f, 0.5f, 0.8f),
    )

    private fun analysis(
        durationMs: Long = 60_000,
        detections: List<ObservedDefect>,
    ) = VideoAnalysis(
        durationMs = durationMs,
        width = 1920,
        height = 1080,
        sampledFps = 2f,
        detections = detections,
    )

    @Test
    fun `consecutive detections of one pothole merge into a single event`() {
        val plan = MontagePlanner.events(
            analysis(
                detections = listOf(
                    detection(10_000, confidence = 0.6f),
                    detection(10_500, confidence = 0.9f),
                    detection(11_000, confidence = 0.7f),
                ),
            ),
        )

        assertEquals(1, plan.size)
        assertEquals(0.9f, plan.first().confidence, 0.0001f)
        assertEquals(10_500L, plan.first().peak.timeMs)
    }

    @Test
    fun `different types at the same moment stay separate events`() {
        val events = MontagePlanner.events(
            analysis(
                detections = listOf(
                    detection(5_000, DefectType.POTHOLE),
                    detection(5_000, DefectType.CRACK_NETWORK),
                ),
            ),
        )

        assertEquals(2, events.size)
        assertEquals(setOf(DefectType.POTHOLE, DefectType.CRACK_NETWORK), events.map { it.type }.toSet())
    }

    @Test
    fun `highlight plan keeps best defect and cuts context around it`() {
        val plan = MontagePlanner.plan(
            analysis(
                durationMs = 20_000,
                detections = listOf(detection(10_000, confidence = 0.95f)),
            ),
            MontageMode.HIGHLIGHTS,
        )

        assertEquals(1, plan.segments.size)
        val segment = plan.segments.first()
        assertEquals(8_500L, segment.startMs)
        assertEquals(12_500L, segment.endMs)
        assertEquals(4_000L, plan.videoDurationMs)
    }

    @Test
    fun `segments are clamped to video bounds`() {
        val plan = MontagePlanner.plan(
            analysis(
                durationMs = 3_000,
                detections = listOf(detection(200, confidence = 0.9f)),
            ),
            MontageMode.HIGHLIGHTS,
        )

        val segment = plan.segments.first()
        assertEquals(0L, segment.startMs)
        assertEquals(2_700L, segment.endMs)
    }

    @Test
    fun `highlight plan skips overlapping candidates and keeps chronology`() {
        val plan = MontagePlanner.plan(
            analysis(
                durationMs = 60_000,
                detections = listOf(
                    detection(30_000, confidence = 0.99f),
                    detection(31_000, confidence = 0.98f),
                    detection(5_000, confidence = 0.8f),
                ),
            ),
            MontageMode.HIGHLIGHTS,
        )

        assertEquals(2, plan.segments.size)
        assertTrue(plan.segments[0].startMs < plan.segments[1].startMs)
        assertEquals(5_000L, plan.segments[0].events.first().peak.timeMs)
    }

    @Test
    fun `highlight plan respects duration budget`() {
        val detections = (0 until 60).map { index ->
            detection(index * 3_000L + 1_000, confidence = 0.9f - index * 0.001f)
        }
        val plan = MontagePlanner.plan(
            analysis(durationMs = 200_000, detections = detections),
            MontageMode.HIGHLIGHTS,
            MontageConfig(maxSegments = 5, maxHighlightDurationMs = 20_000),
        )

        assertTrue(plan.segments.size <= 5)
        assertTrue(plan.videoDurationMs <= 20_000)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `full mode clamps long video and keeps events inside`() {
        val plan = MontagePlanner.plan(
            analysis(
                durationMs = 10 * 60_000,
                detections = listOf(
                    detection(10_000),
                    detection(4 * 60_000),
                    detection(9 * 60_000),
                ),
            ),
            MontageMode.FULL,
            MontageConfig(maxFullDurationMs = 5 * 60_000),
        )

        assertEquals(1, plan.segments.size)
        assertEquals(300_000L, plan.segments.first().endMs)
        assertEquals(2, plan.segments.first().events.size)
    }

    @Test
    fun `empty analysis produces empty plan`() {
        val plan = MontagePlanner.plan(analysis(detections = emptyList()), MontageMode.HIGHLIGHTS)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.eventCount)
    }
}
