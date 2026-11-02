package ai.roadscan.core.calibration

import ai.roadscan.core.agent.AgentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationProfileTest {

    @Test
    fun `default profile is valid and applies to config`() {
        val profile = CalibrationProfile.DEFAULT
        assertTrue(profile.isValid)
        val config = profile.applyTo(AgentConfig.DEFAULT)
        assertEquals(profile.depthBaseMm, config.depthBaseMm)
        assertEquals(profile.depthMmPerG, config.depthMmPerG)
        assertNull(config.cameraFocalLengthPx)
    }

    @Test
    fun `profile rejects physically impossible geometry`() {
        val bad = CalibrationProfile(cameraHeightM = 0.1f, cameraPitchDeg = 90f, focalLengthPx = 10f)
        assertFalse(bad.isValid)
        assertEquals(3, bad.validationErrors().size)
    }

    @Test
    fun `focal length from reference measurement`() {
        // Объект шириной 1.57 м (колея) на дистанции 10 м занимает 480 px -> f ≈ 3057 px
        val f = CalibrationProfile.estimateFocalLengthPx(
            knownWidthM = 1.57f,
            distanceM = 10f,
            widthPx = 480f,
        )
        assertNotNull(f)
        assertTrue(kotlin.math.abs(f - 3057.3f) < 1f)
    }

    @Test
    fun `focal length rejects zero inputs`() {
        assertNull(CalibrationProfile.estimateFocalLengthPx(0f, 10f, 480f))
        assertNull(CalibrationProfile.estimateFocalLengthPx(1.5f, 0f, 480f))
        assertNull(CalibrationProfile.estimateFocalLengthPx(1.5f, 10f, 0f))
    }

    @Test
    fun `round trip through json keeps calibration`() {
        val profile = CalibrationProfile(
            name = "Газель",
            cameraHeightM = 1.15f,
            cameraPitchDeg = 12.5f,
            focalLengthPx = 2650f,
            vehicle = "ГАЗ-3302",
            calibratedAtMs = 1_700_000_000_000,
        )
        val restored = CalibrationProfile.fromJson(CalibrationProfile.toJson(profile))
        assertEquals(profile, restored)
        assertTrue(restored.hasOptics)
    }

    @Test
    fun `broken json falls back to default`() {
        assertEquals(CalibrationProfile.DEFAULT, CalibrationProfile.fromJson("{broken"))
    }
}
