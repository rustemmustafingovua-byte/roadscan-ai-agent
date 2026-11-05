package ai.roadscan.core

import ai.roadscan.core.calibration.CameraOptics
import ai.roadscan.core.calibration.DeviceTuner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutoSetupTest {

    // --- оптика камеры ---

    @Test
    fun `focal length in pixels from camera characteristics`() {
        // Типичный смартфон: фокус 4.2 мм, сенсор 6.4 мм, кадр 1280 px.
        val f = CameraOptics.focalLengthPx(4.2f, 6.4f, 1280)
        assertEquals(840f, f, 0.5f)
    }

    @Test
    fun `focal length scales with frame width`() {
        val wide = CameraOptics.focalLengthPx(4.2f, 6.4f, 1280)
        val narrow = CameraOptics.focalLengthPx(4.2f, 6.4f, 640)
        assertEquals(wide / 2f, narrow, 0.5f)
    }

    @Test
    fun `focal rejects nonsense`() {
        assertFailsWith<IllegalArgumentException> { CameraOptics.focalLengthPx(0f, 6.4f, 1280) }
        assertFailsWith<IllegalArgumentException> { CameraOptics.focalLengthPx(4.2f, -1f, 1280) }
        assertFailsWith<IllegalArgumentException> { CameraOptics.focalLengthPx(4.2f, 6.4f, 0) }
    }

    // --- наклон по тяжотению ---

    @Test
    fun `upright phone means camera along the horizon`() {
        // Вертикально: тяжотение вдоль -Y.
        val pitch = CameraOptics.pitchDegFromGravity(0f, -9.81f, 0f)
        assertEquals(0f, pitch, 0.3f)
    }

    @Test
    fun `tilted back phone looks down`() {
        // Завал на 20°: компонента g_z = 9.81 * sin(20°).
        val tilt = 20.0
        val g = 9.81
        val gy = (-g * kotlin.math.cos(Math.toRadians(tilt))).toFloat()
        val gz = (-g * kotlin.math.sin(Math.toRadians(tilt))).toFloat()
        val pitch = CameraOptics.pitchDegFromGravity(0f, gy, gz)
        assertEquals(20f, pitch, 0.5f)
    }

    @Test
    fun `flat on the table is clamped to a valid profile`() {
        // Экраном вверх, камера в небо: модель даст отрицательный наклон,
        // но профиль не должен выйти за валидный диапазон.
        val pitch = CameraOptics.pitchDegFromGravity(0f, 0f, 9.81f)
        assertTrue(pitch >= -5f && pitch <= 45f, "pitch=$pitch")
    }

    @Test
    fun `free fall is not a measurement`() {
        val pitch = CameraOptics.pitchDegFromGravity(0f, 0f, 0f)
        assertTrue(pitch.isNaN())
    }

    // --- профиль устройства ---

    @Test
    fun `fast device gets full quality`() {
        val t = DeviceTuner.recommend(cpuCores = 8, ramMb = 8000, inferenceMs = 30.0)
        assertEquals(4, t.inferenceThreads)
        assertEquals(30, t.analysisFps)
        assertEquals(1280, t.analysisWidthPx)
    }

    @Test
    fun `mid device drops to 15 fps`() {
        val t = DeviceTuner.recommend(cpuCores = 8, ramMb = 4000, inferenceMs = 60.0)
        assertEquals(15, t.analysisFps)
        assertEquals(960, t.analysisWidthPx)
    }

    @Test
    fun `slow device gets minimal load`() {
        val t = DeviceTuner.recommend(cpuCores = 4, ramMb = 2000, inferenceMs = 350.0)
        assertEquals(6, t.analysisFps)
        assertEquals(640, t.analysisWidthPx)
    }

    @Test
    fun `weak ram device is kept slow even if inference is fast`() {
        val t = DeviceTuner.recommend(cpuCores = 8, ramMb = 2048, inferenceMs = 30.0)
        assertEquals(15, t.analysisFps, "мало памяти — бережём")
    }

    @Test
    fun `threads are capped and never zero`() {
        assertEquals(1, DeviceTuner.recommend(cpuCores = 1, ramMb = 4000, inferenceMs = 30.0).inferenceThreads)
        assertEquals(4, DeviceTuner.recommend(cpuCores = 16, ramMb = 4000, inferenceMs = 30.0).inferenceThreads)
    }

    @Test
    fun `recommendation is deterministic`() {
        val a = DeviceTuner.recommend(8, 6000, 55.0)
        val b = DeviceTuner.recommend(8, 6000, 55.0)
        assertEquals(a, b)
    }
}
