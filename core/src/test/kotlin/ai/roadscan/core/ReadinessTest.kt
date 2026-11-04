package ai.roadscan.core.readiness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadinessTest {

    private fun evaluate(
        permissions: Boolean = true,
        license: Boolean = true,
        calibration: Boolean = true,
        documents: Boolean = true,
        equipment: Boolean = true,
    ) = ReadinessEvaluator.evaluate(
        permissionsGranted = permissions,
        licenseActive = license,
        calibrationReady = calibration,
        documentsFilled = documents,
        equipmentReady = equipment,
    )

    @Test
    fun `everything ready when all checks pass`() {
        val readiness = evaluate()
        assertTrue(readiness.ready)
        assertEquals(0, readiness.pending)
        assertEquals(100, readiness.progress)
    }

    @Test
    fun `missing permissions block the shift`() {
        val readiness = evaluate(permissions = false)
        assertFalse(readiness.ready)
        assertEquals(1, readiness.blockingPending)
        assertFalse(readiness.statusOf(ReadinessStepId.PERMISSIONS))
        assertTrue(readiness.statusOf(ReadinessStepId.LICENSE))
    }

    @Test
    fun `missing equipment also blocks the shift`() {
        val readiness = evaluate(equipment = false)
        assertEquals(1, readiness.blockingPending)
        assertFalse(readiness.statusOf(ReadinessStepId.EQUIPMENT))
    }

    @Test
    fun `missing license or calibration does not block but is visible`() {
        val readiness = evaluate(license = false, calibration = false)
        assertFalse(readiness.ready)
        assertEquals(0, readiness.blockingPending)
        assertEquals(2, readiness.pending)
        assertEquals(60, readiness.progress)
    }

    @Test
    fun `progress counts completed steps`() {
        val readiness = evaluate(documents = false, equipment = false)
        assertEquals(60, readiness.progress)
        assertEquals(1, readiness.blockingPending)
    }
}
