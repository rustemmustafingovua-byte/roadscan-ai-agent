package ai.roadscan.core

import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.perception.LensQualityMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LensQualityMonitorTest {

    @Test
    fun `ten low contrast frames trigger dirty lens warning`() {
        var warned = 0
        val monitor = LensQualityMonitor(AgentConfig.DEFAULT) { warned++ }

        repeat(10) { monitor.onFrame(contrast = 0.01f) }

        assertTrue(monitor.warningActive)
        assertEquals(1, warned)
        assertEquals(10, monitor.consecutiveBadFrames)
    }

    @Test
    fun `good frame resets streak`() {
        val monitor = LensQualityMonitor()
        repeat(9) { monitor.onFrame(0.01f) }
        monitor.onFrame(0.5f)
        assertEquals(0, monitor.consecutiveBadFrames)
        assertFalse(monitor.warningActive)
    }

    @Test
    fun `dirt pattern blocks frame even with normal contrast`() {
        val monitor = LensQualityMonitor()
        assertTrue(monitor.onFrame(contrast = 0.6f, dirtPatternScore = 0.9f))
    }

    @Test
    fun `warning is not repeated while camera stays dirty`() {
        var warned = 0
        val monitor = LensQualityMonitor(AgentConfig.DEFAULT) { warned++ }
        repeat(30) { monitor.onFrame(0.0f) }
        assertEquals(1, warned)
    }
}
