package ai.roadscan.core.license

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseStateTest {

    private val dayMs = 86_400_000L

    @Test
    fun `trial starts with configured limits`() {
        val now = System.currentTimeMillis()
        val state = LicenseState(trialStartedAtMs = now)
        assertTrue(state.isTrialActive(now))
        assertEquals(Edition.TRIAL.trialSessions, state.trialSessionsLeft())
        assertEquals(Edition.TRIAL.trialDays, state.trialDaysLeft(now))
        assertTrue(state.demoWatermark(now))
        assertTrue(state.canExport())
    }

    @Test
    fun `trial expires by days`() {
        val start = System.currentTimeMillis()
        val state = LicenseState(trialStartedAtMs = start)
        val afterTrial = start + (Edition.TRIAL.trialDays + 1) * dayMs
        assertFalse(state.isTrialActive(afterTrial))
        assertFalse(state.canExport(afterTrial))
    }

    @Test
    fun `trial expires by sessions`() {
        val start = System.currentTimeMillis()
        val state = LicenseState(trialStartedAtMs = start, sessionsUsed = Edition.TRIAL.trialSessions)
        assertEquals(0, state.trialSessionsLeft())
        assertFalse(state.isTrialActive(start))
        assertFalse(state.canExport(start))
    }

    @Test
    fun `activated license disables watermark and unlocks gis`() {
        val payload = LicensePayload(
            licenseId = "L-1",
            organization = "КП Шляхове господарство",
            tier = "PRO",
            expiresAtMs = 0,
            features = listOf("pdf", "xlsx", "geojson", "gis"),
        )
        val state = LicenseState(edition = Edition.PRO, payload = payload, trialStartedAtMs = 1)
        assertTrue(state.isLicensed(1_700_000_000_000))
        assertFalse(state.demoWatermark(1_700_000_000_000))
        assertTrue(state.hasFeature("gis", 1_700_000_000_000))
        assertFalse(state.hasFeature("api", 1_700_000_000_000))
        assertTrue(state.summary(1_700_000_000_000).contains("безстроково"))
    }

    @Test
    fun `expired license is not usable`() {
        val payload = LicensePayload(
            licenseId = "L-2",
            expiresAtMs = 1_600_000_000_000,
            features = listOf("pdf"),
        )
        val state = LicenseState(edition = Edition.PRO, payload = payload)
        assertFalse(state.isLicensed(1_700_000_000_000))
        assertTrue(state.demoWatermark(1_700_000_000_000))
        assertFalse(state.canExport(1_700_000_000_000))
    }
}
