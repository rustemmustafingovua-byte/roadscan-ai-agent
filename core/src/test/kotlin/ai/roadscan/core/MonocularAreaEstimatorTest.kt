package ai.roadscan.core

import ai.roadscan.core.geo.MonocularAreaEstimator
import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.VisionDetection
import kotlin.test.Test
import kotlin.test.assertTrue

class MonocularAreaEstimatorTest {

    private val estimator = MonocularAreaEstimator()

    private fun detection(left: Float, top: Float, right: Float, bottom: Float) =
        VisionDetection(ai.roadscan.core.model.DefectType.POTHOLE, 0.9f, BoundingBox(left, top, right, bottom))

    @Test
    fun `pothole about one square metre is estimated within tolerance`() {
        // 4 м до нижней кромки: bbox ~0.23 кадра в ширину и ~0.04 в высоту.
        val frame = Fixtures.frame(detection = null)
        val result = estimator.estimate(detection(0.30f, 0.545f, 0.52f, 0.585f), frame)

        assertTrue(result.areaM2 in 0.5..1.8, "area=${result.areaM2} distance=${result.distanceM}")
        assertTrue(result.distanceM in 3.0..6.0, "distance=${result.distanceM}")
    }

    @Test
    fun `same object farther away is mapped to larger distance and scale`() {
        val frame = Fixtures.frame(detection = null)
        val near = estimator.estimate(detection(0.3f, 0.70f, 0.5f, 0.75f), frame)
        val far = estimator.estimate(detection(0.3f, 0.54f, 0.5f, 0.59f), frame)
        assertTrue(near.distanceM < far.distanceM, "near=${near.distanceM} far=${far.distanceM}")
        assertTrue(near.widthM < far.widthM, "same bbox fraction is physically wider farther away")
    }

    @Test
    fun `area never exceeds sanity limits`() {
        val frame = Fixtures.frame(detection = null)
        val huge = estimator.estimate(detection(0f, 0f, 1f, 1f), frame)
        assertTrue(huge.areaM2 <= 500.0)
        assertTrue(huge.areaM2 > 0.0)
    }

    @Test
    fun `calibrated focal length is honoured`() {
        val frame = Fixtures.frame(detection = null, imageWidth = 1920)
        val withDefault = MonocularAreaEstimator()
        val calibrated = MonocularAreaEstimator(ai.roadscan.core.agent.AgentConfig(cameraFocalLengthPx = 1400f))
        val d = detection(0.4f, 0.7f, 0.6f, 0.76f)
        assertTrue(withDefault.estimate(d, frame).distanceM != calibrated.estimate(d, frame).distanceM)
    }
}
