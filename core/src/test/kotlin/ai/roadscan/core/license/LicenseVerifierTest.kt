package ai.roadscan.core.license

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicenseVerifierTest {

    private val originalKey = LicenseVerifier.publicKeyBase64

    // Тестовая пара ключей P-256; закрытая часть существует только в этом файле.
    private val testPublicKey =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEMIvvQGRliVP5oQ2DOnyhBllWXJyF4U6/y3ZP7iaBXTsRFhb9pHtFl6G1cZTlevUYEVLjRvQwyFfaEb6D1lhFqw=="

    private val validCode =
        "RSL1.eyJleHBpcmVzQXRNcyI6NDEwMjQ0NDgwMDAwMCwiZmVhdHVyZXMiOlsicGRmIiwieGxzeCIsImdlb2pzb24iLCJnaXMiXSwiaXNzdWVkQXRNcyI6MTcwMDAwMDAwMDAwMCwibGljZW5zZUlkIjoiVEVTVC0wMDEiLCJtYXhTZXNzaW9ucyI6MCwib3JnYW5pemF0aW9uIjoi0JzQkdCjINCU0L7RgNGA0LXQvNGB0YLRgNC-0LkiLCJwcm9kdWN0Ijoicm9hZHNjYW4tYWkiLCJzZWF0cyI6MiwidGllciI6IlBSTyJ9.MEYCIQDzC1rtqCmw9O5N2hujauz-X5ItPl4ytLOEWpESQFl1_AIhALSTDY43jcsOq9N3MUAlr76Yr4j2MIsaIf2IExN0-WvJ"

    private val expiredCode =
        "RSL1.eyJleHBpcmVzQXRNcyI6MTAwMDAwMDAwMDAwMCwiZmVhdHVyZXMiOlsicGRmIiwieGxzeCIsImdlb2pzb24iLCJnaXMiXSwiaXNzdWVkQXRNcyI6MTcwMDAwMDAwMDAwMCwibGljZW5zZUlkIjoiVEVTVC1FWFBJUkVEIiwibWF4U2Vzc2lvbnMiOjAsIm9yZ2FuaXphdGlvbiI6ItCc0JHQoyDQlNC-0YDRgNC10LzRgdGC0YDQvtC5IiwicHJvZHVjdCI6InJvYWRzY2FuLWFpIiwic2VhdHMiOjIsInRpZXIiOiJQUk8ifQ.MEQCIBdm6hrexhVSU5Q6k-A_j1-IlDEyhmOEqauuTqHgMCtRAiAvEYWQ9b4Y_qR3HlN2Ra4O2rSyt3FMEmkPcDMhFq8tGA"

    private val otherProductCode =
        "RSL1.eyJleHBpcmVzQXRNcyI6NDEwMjQ0NDgwMDAwMCwiZmVhdHVyZXMiOlsicGRmIiwieGxzeCIsImdlb2pzb24iLCJnaXMiXSwiaXNzdWVkQXRNcyI6MTcwMDAwMDAwMDAwMCwibGljZW5zZUlkIjoiVEVTVC0wMDEiLCJtYXhTZXNzaW9ucyI6MCwib3JnYW5pemF0aW9uIjoi0JzQkdCjINCU0L7RgNGA0LXQvNGB0YLRgNC-0LkiLCJwcm9kdWN0Ijoib3RoZXItcHJvZHVjdCIsInNlYXRzIjoyLCJ0aWVyIjoiUFJPIn0.MEUCIQCVwaiAPVlCb7S1w21g7z9CEOmPuENXKXvqAwcIK4r4KQIgOq8rLyJyt0VZYcqGQ4L7aQ4OEX6GJnxSJnMGxTwklzA"

    @BeforeTest
    fun setUp() {
        LicenseVerifier.publicKeyBase64 = testPublicKey
    }

    @AfterTest
    fun tearDown() {
        LicenseVerifier.publicKeyBase64 = originalKey
    }

    @Test
    fun `valid license is accepted`() {
        val check = LicenseVerifier.verify(validCode, nowMs = 1_800_000_000_000)
        val payload = assertIs<LicenseCheck.Valid>(check).payload
        assertEquals("TEST-001", payload.licenseId)
        assertEquals("МБУ Дорремстрой", payload.organization)
        assertEquals("PRO", payload.tier)
        assertTrue(payload.hasFeature("gis"))
        assertFalse(payload.hasFeature("api"))
    }

    @Test
    fun `expired license reports expiry instead of validity`() {
        val check = LicenseVerifier.verify(expiredCode, nowMs = 1_800_000_000_000)
        assertIs<LicenseCheck.Expired>(check)
        assertFalse(check.isUsable)
    }

    @Test
    fun `license of another product is rejected`() {
        val check = LicenseVerifier.verify(otherProductCode, nowMs = 1_800_000_000_000)
        val invalid = assertIs<LicenseCheck.Invalid>(check)
        assertEquals("WRONG_PRODUCT", invalid.code)
    }

    @Test
    fun `tampered payload is rejected`() {
        val parts = validCode.split('.')
        val tampered = "RSL1.${parts[1].dropLast(2)}AA.${parts[2]}"
        assertIs<LicenseCheck.Invalid>(LicenseVerifier.verify(tampered, 1_800_000_000_000))
    }

    @Test
    fun `garbage input is rejected`() {
        assertIs<LicenseCheck.Invalid>(LicenseVerifier.verify("not-a-license"))
        assertIs<LicenseCheck.Invalid>(LicenseVerifier.verify("RSL1.abc"))
    }
}
