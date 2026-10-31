package ai.roadscan.core

import ai.roadscan.core.perception.AdaptiveFrameGovernor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveFrameGovernorTest {

    @Test
    fun `normal conditions run at max fps`() {
        val governor = AdaptiveFrameGovernor()
        val d = governor.update(cpuTempC = 35f, batteryPercent = 80)
        assertEquals(30, d.targetFps)
        assertEquals(AdaptiveFrameGovernor.Mode.NORMAL, d.mode)
        assertTrue(governor.shouldAnalyzeNextFrame())
    }

    @Test
    fun `soc above 42 drops analysis to 15 fps`() {
        val governor = AdaptiveFrameGovernor()
        val d = governor.update(cpuTempC = 43f, batteryPercent = 80)
        assertEquals(15, d.targetFps)
        assertEquals(2, d.frameStride)
        assertEquals(AdaptiveFrameGovernor.Mode.THERMAL_THROTTLE, d.mode)
    }

    @Test
    fun `soc above 46 drops to emergency 6 fps`() {
        val governor = AdaptiveFrameGovernor()
        val d = governor.update(cpuTempC = 47f, batteryPercent = 80)
        assertEquals(6, d.targetFps)
        assertEquals(5, d.frameStride)
        assertEquals(AdaptiveFrameGovernor.Mode.EMERGENCY, d.mode)
    }

    @Test
    fun `hysteresis keeps throttle until cooled below 40`() {
        val governor = AdaptiveFrameGovernor()
        governor.update(cpuTempC = 43f, batteryPercent = 80)
        val stillHot = governor.update(cpuTempC = 41f, batteryPercent = 80)
        assertEquals(AdaptiveFrameGovernor.Mode.THERMAL_THROTTLE, stillHot.mode)

        val cooled = governor.update(cpuTempC = 39f, batteryPercent = 80)
        assertEquals(30, cooled.targetFps)
        assertEquals(AdaptiveFrameGovernor.Mode.NORMAL, cooled.mode)
    }

    @Test
    fun `low battery switches to saver mode`() {
        val governor = AdaptiveFrameGovernor()
        val d = governor.update(cpuTempC = 35f, batteryPercent = 15)
        assertEquals(15, d.targetFps)
        assertEquals(AdaptiveFrameGovernor.Mode.BATTERY_SAVER, d.mode)
    }

    @Test
    fun `critical battery switches to emergency`() {
        val governor = AdaptiveFrameGovernor()
        val d = governor.update(cpuTempC = 35f, batteryPercent = 5)
        assertEquals(6, d.targetFps)
        assertEquals(AdaptiveFrameGovernor.Mode.EMERGENCY, d.mode)
    }

    @Test
    fun `frame stride skips every second frame while throttled`() {
        val governor = AdaptiveFrameGovernor()
        governor.update(cpuTempC = 44f, batteryPercent = 80)
        val analyzed = (1..10).count { governor.shouldAnalyzeNextFrame() }
        assertEquals(5, analyzed)
    }
}
