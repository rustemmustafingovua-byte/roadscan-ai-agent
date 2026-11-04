package ai.roadscan.core

import ai.roadscan.core.fusion.SensorFusionEngine
import ai.roadscan.core.model.SensorSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Граница «Android-сенсор -> ядро».
 *
 * Два дефекта, которые ломали сенсорную фузию в поле:
 *
 *  1. Android отдаёт ускорение в м/с², ядро работало в g. В покой давало
 *     deltaG ≈ 8.8, и любой визуальный кандидат подтверждался как удар.
 *  2. Величина удара считалась по одной оси Z. На лобовом стекле ось Z не
 *     совпадает с направлением удара, поэтому «покой» выглядел как удар.
 */
class SensorUnitsTest {

    private val g = SensorSample.STANDARD_GRAVITY_MPS2

    @Test
    fun `rest reading in m s2 is normalized to one g`() {
        val sample = SensorSample.fromMetersPerSecondSquared(
            timestampMs = 1_000L,
            xMps2 = 0f,
            yMps2 = 0f,
            zMps2 = g,
        )

        assertEquals(1.0f, sample.zGForce, 1e-4f)
        assertEquals(0.0f, sample.deltaG, 1e-4f)
    }

    @Test
    fun `impact of 0_4 g is recognized as 0_4 g`() {
        val sample = SensorSample.fromMetersPerSecondSquared(
            timestampMs = 1_000L,
            xMps2 = 0f,
            yMps2 = 0f,
            zMps2 = g + 0.4f * g,
        )

        assertEquals(0.4f, sample.deltaG, 1e-4f)
    }

    @Test
    fun `flat road does not produce an impact in the fusion buffer`() {
        val engine = SensorFusionEngine()
        for (t in 0L..200L step 5) {
            engine.addSample(SensorSample.fromMetersPerSecondSquared(t, 0f, 0f, g))
        }

        val peak = engine.peakAround(100L, windowMs = 50L)
        assertNotNull(peak)
        assertTrue(
            peak.deltaG < 0.05f,
            "Покой не должен выглядеть как удар: deltaG=${peak.deltaG}",
        )
    }

    @Test
    fun `real impact in m s2 crosses the confirmation threshold`() {
        val engine = SensorFusionEngine()
        for (t in 0L..200L step 5) {
            val z = if (t == 100L) g + 0.9f * g else g
            engine.addSample(SensorSample.fromMetersPerSecondSquared(t, 0f, 0f, z))
        }

        val peak = engine.peakAround(100L, windowMs = 50L)
        assertNotNull(peak)
        assertEquals(0.9f, peak.deltaG, 1e-3f)
    }

    @Test
    fun `mounting orientation does not change the resting delta`() {
        // Телефон на лобовом стекле: ось Z почти горизонтальна, тяжесть
        // лежит в XY. Модуль ускорения в покое всё равно равен 1 g.
        val vertical = SensorSample.fromMetersPerSecondSquared(
            timestampMs = 1_000L,
            xMps2 = 0.98f * g,
            yMps2 = 0f,
            zMps2 = 0.2f * g,
        )

        assertEquals(1.0f, vertical.magnitudeG, 1e-3f)
        assertTrue(vertical.deltaG < 0.01f, "Покой не должен зависеть от ориентации")
    }

    @Test
    fun `flipped phone is still resting`() {
        val flipped = SensorSample.fromMetersPerSecondSquared(
            timestampMs = 1_000L,
            xMps2 = 0f,
            yMps2 = 0f,
            zMps2 = -g,
        )

        assertEquals(1.0f, flipped.magnitudeG, 1e-3f)
        assertTrue(flipped.deltaG < 0.01f)
    }

    @Test
    fun `vertical pothole shock is detected while braking is suppressed`() {
        // Удар о яму направлен вдоль тяжести: модуль растёт.
        val pothole = SensorSample.fromMetersPerSecondSquared(1_000L, 0f, 0f, g + 0.5f * g)
        assertEquals(0.5f, pothole.deltaG, 1e-3f)

        // Торможение направлено поперёк тяжести: модуль почти не меняется.
        val braking = SensorSample.fromMetersPerSecondSquared(1_000L, 0.4f * g, 0f, g)
        assertTrue(
            braking.deltaG < 0.1f,
            "Торможение не должно подтверждаться как удар: deltaG=${braking.deltaG}",
        )
    }

    @Test
    fun `orientation independent shock is found in the buffer`() {
        val engine = SensorFusionEngine()
        // Телефон лежит «плашмя», удар приходит по вертикали.
        for (t in 0L..200L step 5) {
            val z = if (t == 100L) g + 0.8f * g else g
            engine.addSample(SensorSample.fromMetersPerSecondSquared(t, 0f, 0f, z))
        }

        val peak = engine.peakAround(100L, windowMs = 50L)
        assertNotNull(peak)
        assertEquals(100L, peak.timestampMs)
        assertTrue(peak.deltaG > 0.75f)
    }
}
