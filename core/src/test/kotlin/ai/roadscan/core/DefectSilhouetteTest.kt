package ai.roadscan.core

import ai.roadscan.core.model.DefectType
import ai.roadscan.core.video.DefectSilhouette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefectSilhouetteTest {

    private val w = 32
    private val h = 32

    /** Светлое полотно с тёмным пятном в центре — как яма. */
    private fun potholeScene(): IntArray {
        val luma = IntArray(w * h) { 170 }
        for (y in 10..21) for (x in 11..20) luma[y * w + x] = 60
        return luma
    }

    @Test
    fun `pothole silhouette covers the dark spot`() {
        val mask = DefectSilhouette.mask(potholeScene(), w, h, DefectType.POTHOLE)
        // Центр тёмного пятна — внутри силуэта.
        assertTrue(mask[15 * w + 15])
        // Углы кадра — вне.
        assertFalse(mask[0])
        assertFalse(mask[w * h - 1])
    }

    @Test
    fun `pothole silhouette does not spread over clean road`() {
        val mask = DefectSilhouette.mask(potholeScene(), w, h, DefectType.POTHOLE)
        val inside = mask.count { it }
        // Силуэт не больше площади эллипса (~π/4 от кадра) с запасом.
        assertTrue(inside < w * h / 2, "силуэт занял $inside из ${w * h}")
    }

    @Test
    fun `textureless frame falls back to ellipse shape`() {
        val flat = IntArray(w * h) { 128 }
        val mask = DefectSilhouette.mask(flat, w, h, DefectType.POTHOLE)
        val inside = mask.count { it }
        // Эллипс ≈ 78% кадра: пустая маска или сплошной квадрат — оба провал.
        assertTrue(inside > w * h / 4, "слишком мало: $inside")
        assertTrue(inside < w * h * 7 / 8, "слишком много: $inside")
    }

    @Test
    fun `crack silhouette follows contrast line`() {
        // Тёмное полотно со светлой линией поперёк — как трещина/разметка.
        val luma = IntArray(w * h) { 80 }
        for (x in 0 until w) for (y in 15..17) luma[y * w + x] = 200
        val mask = DefectSilhouette.mask(luma, w, h, DefectType.CRACK_NETWORK)
        assertTrue(mask[16 * w + 16], "линия должна быть в силуэте")
        assertFalse(mask[4 * w + 4], "угол не должен гореть")
    }

    @Test
    fun `silhouette is symmetric on symmetric scene`() {
        val mask = DefectSilhouette.mask(potholeScene(), w, h, DefectType.POTHOLE)
        for (y in 0 until h) {
            for (x in 0 until w) {
                assertEquals(mask[y * w + x], mask[y * w + (w - 1 - x)], "x=$x y=$y")
            }
        }
    }

    @Test
    fun `tiny grid is handled`() {
        val mask = DefectSilhouette.mask(IntArray(1) { 0 }, 1, 1, DefectType.POTHOLE)
        assertEquals(1, mask.size)
    }

    @Test
    fun `mismatched grid is rejected`() {
        assertFails { DefectSilhouette.mask(IntArray(5), 2, 2, DefectType.POTHOLE) }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("ожидался IllegalArgumentException")
    }
}
