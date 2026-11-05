package ai.roadscan.core

import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.perception.RoadSurfaceFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RoadSurfaceFilterTest {

    private val w = 96
    private val h = 54

    /** Ровное серое поле с необязательным «дефектом» — тёмным пятном. */
    private fun scene(
        base: Int = 120,
        hole: Boolean = false,
        holeValue: Int = 60,
    ): Pair<IntArray, IntArray> {
        val gray = IntArray(w * h) { base }
        if (hole) {
            for (y in 25..35) for (x in 40..55) gray[y * w + x] = holeValue
        }
        return gray to RoadSurfaceFilter.integral(gray, w, h)
    }

    private fun fullRoadMask(): ByteArray = ByteArray(w * h) { 1 }

    private fun box(l: Float, t: Float, r: Float, b: Float) = BoundingBox(l, t, r, b)

    @Test
    fun `tiny box is rejected`() {
        val (gray, integral) = scene()
        val reason = RoadSurfaceFilter.rejectReason(
            box = box(0.5f, 0.5f, 0.52f, 0.51f),
            type = DefectType.POTHOLE, confidence = 0.9f,
            gray = gray, integral = integral, roadMask = fullRoadMask(),
            width = w, height = h,
        )
        assertEquals("tiny", reason)
    }

    @Test
    fun `offroad box is rejected by the road mask`() {
        val (gray, integral) = scene()
        // Маска дороги — только нижняя половина, рамка в верхней.
        val mask = ByteArray(w * h) { i -> if (i / w > h / 2) 1 else 0 }
        val reason = RoadSurfaceFilter.rejectReason(
            box = box(0.2f, 0.1f, 0.5f, 0.3f),
            type = DefectType.POTHOLE, confidence = 0.9f,
            gray = gray, integral = integral, roadMask = mask,
            width = w, height = h,
        )
        assertEquals("offroad", reason)
    }

    @Test
    fun `box on the road passes the mask check`() {
        val (gray, integral) = scene(hole = true)
        val mask = ByteArray(w * h) { i -> if (i / w > h / 2) 1 else 0 }
        val reason = RoadSurfaceFilter.rejectReason(
            box = box(0.35f, 0.55f, 0.65f, 0.75f),
            type = DefectType.POTHOLE, confidence = 0.9f,
            gray = gray, integral = integral, roadMask = mask,
            width = w, height = h,
        )
        assertNull(reason)
    }

    @Test
    fun `weak pothole is rejected`() {
        val (gray, integral) = scene(hole = true)
        val reason = RoadSurfaceFilter.rejectReason(
            box = box(0.35f, 0.55f, 0.65f, 0.75f),
            type = DefectType.POTHOLE, confidence = 0.3f,
            gray = gray, integral = integral, roadMask = fullRoadMask(),
            width = w, height = h,
        )
        assertEquals("pothole-weak", reason)
    }

    @Test
    fun `linear defect on bright band is rejected as marking`() {
        // Светлая широкая «полоса» внизу — как разметка.
        val gray = IntArray(w * h) { 80 }
        for (y in 30..45) for (x in 0 until w) gray[y * w + x] = 230
        val integral = RoadSurfaceFilter.integral(gray, w, h)
        val reason = RoadSurfaceFilter.rejectReason(
            // Рамка захватывает и тёмное полотно, и светлую полосу:
            // медиана тёмная, а четверть пикселей ярче медианы + 46.
            box = box(0.1f, 0.22f, 0.9f, 0.84f),
            type = DefectType.RUTTING, confidence = 0.8f,
            gray = gray, integral = integral, roadMask = fullRoadMask(),
            width = w, height = h,
        )
        assertEquals("marking/shadow-edge", reason)
    }

    @Test
    fun `textureless low confidence patch is rejected`() {
        // Полностью ровное поле: градиентов нет, уверенность низкая.
        val (gray, integral) = scene(base = 140)
        val reason = RoadSurfaceFilter.rejectReason(
            box = box(0.2f, 0.3f, 0.7f, 0.8f),
            type = DefectType.PATCH, confidence = 0.25f,
            gray = gray, integral = integral, roadMask = fullRoadMask(),
            width = w, height = h,
        )
        assertEquals("no-texture", reason)
    }

    @Test
    fun `skip texture mode only checks road and size`() {
        val (gray, integral) = scene(base = 140)
        val reason = RoadSurfaceFilter.rejectReason(
            box = box(0.2f, 0.3f, 0.7f, 0.8f),
            type = DefectType.POTHOLE, confidence = 0.2f,
            gray = gray, integral = integral, roadMask = fullRoadMask(),
            width = w, height = h, skipTexture = true,
        )
        assertNull(reason)
    }

    @Test
    fun `no mask means no offroad check`() {
        val (gray, integral) = scene(hole = true)
        val reason = RoadSurfaceFilter.rejectReason(
            box = box(0.35f, 0.55f, 0.65f, 0.75f),
            type = DefectType.POTHOLE, confidence = 0.9f,
            gray = gray, integral = integral, roadMask = null,
            width = w, height = h,
        )
        assertNull(reason)
    }

    @Test
    fun `grayscale and integral match the reference formula`() {
        val argb = IntArray(4) { (0xFF shl 24) or (100 shl 16) or (200 shl 8) or 50 }
        val gray = RoadSurfaceFilter.grayscale(argb, 2, 2)
        // 0.3*100 + 0.6*200 + 0.1*50 = 30 + 120 + 5 = 155
        assertEquals(155, gray[0])
        val integral = RoadSurfaceFilter.integral(gray, 2, 2)
        assertEquals(0, integral[0])
        assertEquals(155, integral[1 * 3 + 1]) // (1,1) = сумма (0,0)
        assertEquals(620, integral[3 * 3 - 1]) // (2,2) = 4 пикселя
    }

    @Test
    fun `pothole with texture on the road is accepted`() {
        // Кадр с ямой: тёмное пятно на светлом полотне — сильный градиент.
        val (gray, integral) = scene(base = 170, hole = true, holeValue = 40)
        val reason = RoadSurfaceFilter.rejectReason(
            box = box(0.35f, 0.4f, 0.65f, 0.75f),
            type = DefectType.POTHOLE, confidence = 0.85f,
            gray = gray, integral = integral, roadMask = fullRoadMask(),
            width = w, height = h,
        )
        assertNull(reason, "настоящая яма должна проходить фильтр, а была: $reason")
    }

    @Test
    fun `filter is deterministic`() {
        val (gray, integral) = scene(hole = true)
        val args = listOf(
            DefectType.POTHOLE to 0.9f,
            DefectType.CRACK_NETWORK to 0.4f,
        )
        for ((type, conf) in args) {
            val a = RoadSurfaceFilter.rejectReason(
                box(0.3f, 0.5f, 0.7f, 0.8f), type, conf,
                gray, integral, fullRoadMask(), w, h,
            )
            val b = RoadSurfaceFilter.rejectReason(
                box(0.3f, 0.5f, 0.7f, 0.8f), type, conf,
                gray, integral, fullRoadMask(), w, h,
            )
            assertEquals(a, b)
        }
        assertNotNull(args)
    }
}
