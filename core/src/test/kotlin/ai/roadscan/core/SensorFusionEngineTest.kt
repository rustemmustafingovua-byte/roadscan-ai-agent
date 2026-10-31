package ai.roadscan.core

import ai.roadscan.core.fusion.SensorFusionEngine
import ai.roadscan.core.model.SensorSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorFusionEngineTest {

    @Test
    fun `finds peak inside plus minus 50 ms window`() {
        val engine = SensorFusionEngine()
        for (t in 0..200 step 5) {
            engine.addSample(SensorSample(timestampMs = t.toLong(), zGForce = 1.0f))
        }
        engine.addSample(SensorSample(timestampMs = 100, zGForce = 1.8f))

        val peak = engine.peakAround(frameTimestampMs = 100)
        assertNotNull(peak)
        assertEquals(100, peak.timestampMs)
        assertTrue(peak.deltaG > 0.75f)
    }

    @Test
    fun `ignores impact outside window`() {
        val engine = SensorFusionEngine()
        engine.addSample(SensorSample(timestampMs = 0, zGForce = 2.2f))
        engine.addSample(SensorSample(timestampMs = 300, zGForce = 1.0f))

        val peak = engine.peakAround(frameTimestampMs = 300)
        assertNotNull(peak)
        assertTrue(peak.deltaG < 0.1f)
    }

    @Test
    fun `returns null when buffer is empty`() {
        assertNull(SensorFusionEngine().peakAround(1234))
    }

    @Test
    fun `trims old samples`() {
        val engine = SensorFusionEngine(bufferWindowMs = 1_000)
        for (t in 0..5_000 step 10) {
            engine.addSample(SensorSample(t.toLong(), 1f))
        }
        assertTrue(engine.size <= 101)
        assertNull(engine.peakAround(0))
    }

    @Test
    fun `smoothed road noise below threshold is not an impact`() {
        val engine = SensorFusionEngine()
        for (t in 0..100 step 5) {
            engine.addSample(SensorSample(t.toLong(), 1.05f))
        }
        val peak = engine.peakAround(50)
        assertNotNull(peak)
        assertTrue(peak.deltaG < 0.28f)
    }
}
