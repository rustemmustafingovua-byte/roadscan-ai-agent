package ai.roadscan.core

import ai.roadscan.core.fusion.SeverityScorer
import ai.roadscan.core.model.SensorPeak
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeverityScorerTest {

    private val scorer = SeverityScorer()

    @Test
    fun `deep confirmed pothole with hard impact is critical`() {
        val score = scorer.score(
            detection = Fixtures.detection(confidence = 0.95f),
            sensor = SensorPeak(1_000_000, deltaG = 1.4f),
            areaM2 = 2.2,
            depthMm = 70.0,
            impactThresholdG = 0.44f,
        )
        assertTrue(score >= 4, "score=$score")
    }

    @Test
    fun `visual only shadow stays low severity`() {
        val score = scorer.score(
            detection = Fixtures.detection(confidence = 0.45f),
            sensor = null,
            areaM2 = 0.2,
            depthMm = 10.0,
            impactThresholdG = 0.44f,
        )
        assertTrue(score <= 2, "score=$score")
    }

    @Test
    fun `severity is always within 1 to 5`() {
        val values = listOf(0.01, 0.5, 2.0, 10.0).flatMap { area ->
            listOf(0.0, 0.2, 1.0, 2.0).map { g ->
                scorer.score(Fixtures.detection(), SensorPeak(0, g.toFloat()), area, g * 40)
            }
        }
        assertTrue(values.all { it in 1..5 })
    }

    @Test
    fun `larger area increases severity`() {
        val small = scorer.score(Fixtures.detection(confidence = 0.9f), null, 0.05, 5.0, 0.44f)
        val large = scorer.score(Fixtures.detection(confidence = 0.9f), null, 8.0, 5.0, 0.44f)
        assertTrue(large > small)
    }

    @Test
    fun `impact below threshold adds nothing`() {
        val noImpact = scorer.score(Fixtures.detection(confidence = 0.9f), null, 1.0, 30.0, 0.44f)
        val weakImpact = scorer.score(
            Fixtures.detection(confidence = 0.9f),
            SensorPeak(0, 0.1f),
            1.0,
            30.0,
            0.44f,
        )
        assertEquals(noImpact, weakImpact)
    }
}
