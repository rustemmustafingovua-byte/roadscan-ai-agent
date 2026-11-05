package ai.roadscan.core.perception

import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.DefectType
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Привязка детекции к проезжей части: отбраковка находок, которые
 * «привязаны к картинке», а не к дороге.
 *
 * Идея взята из эталонного RoadScan (fpCheck): дефект должен лежать на
 * дорожном полотне (по маске сегментации) и иметь текстуру дефекта, а не
 * разметки/тени/однородной полосы. Причины отказа:
 *
 *  - `tiny` — рамка меньше 3 px в сетке анализа: габарит не определить;
 *  - `offroad` — расширенная рамка почти не пересекает маску дороги
 *    (< 5% пикселей) — объект вне полотна (бордюр, дерево, здание);
 *  - `pothole-weak` — яма с уверенностью ниже 0.5: скорее тень/пятно;
 *  - `marking/shadow-edge` — линейный дефект на светлом фоне (разметка);
 *  - `no-texture` — слабый градиент при низкой уверенности: гладкое пятно;
 *  - `uniform-band` — однородная полоса без контраста (вздутие разметки).
 *
 * Вход — серая карта [gray] (по 0.255) малого кадра (обычно 480 px по
 * ширине), её интеграл [integral] ((h+1)×(w+1)) и байтовая маска дороги
 * [roadMask] (0 — не дорога; иначе дорога). Координаты рамки — нормализованные
 * (0..1) и приводятся к сетке [width]×[height] анализа.
 */
object RoadSurfaceFilter {

    /** Причина отбраковки; null — детекция принята. */
    fun rejectReason(
        box: BoundingBox,
        type: DefectType,
        confidence: Float,
        gray: IntArray,
        integral: IntArray,
        roadMask: ByteArray?,
        width: Int,
        height: Int,
        checkRoad: Boolean = true,
        skipTexture: Boolean = false,
    ): String? {
        val maxX = width - 1
        val maxY = height - 1
        val x0 = clamp((box.left * width).toInt(), 0, maxX)
        val x1 = clamp((box.right * width).toInt(), 0, maxX)
        val y0 = clamp((box.top * height).toInt(), 0, maxY)
        val y1 = clamp((box.bottom * height).toInt(), 0, maxY)
        val bw = x1 - x0
        val bh = y1 - y0
        if (bw < 3 || bh < 3) return "tiny"

        if (checkRoad && roadMask != null) {
            // Расширяем рамку на её же размер и смотрим долю пикселей дороги.
            val ty = max(0, y0 - bh)
            val by = min(maxY, y1 + bh)
            val lx = max(0, x0 - bw)
            val rx = min(maxX, x1 + bw)
            var samples = 0
            var road = 0
            var y = ty
            while (y <= by) {
                var x = lx
                while (x <= rx) {
                    samples++
                    if (roadMask[y * width + x] != 0.toByte()) road++
                    x += 2
                }
                y += 2
            }
            if (samples > 0 && road.toFloat() / samples < OFFROAD_MIN_ROAD_FRACTION) return "offroad"
        }

        if (skipTexture) return null

        // Яма без уверенной текстуры — почти всегда тень.
        if (type == DefectType.POTHOLE && confidence < WEAK_POTHOLE_CONFIDENCE) return "pothole-weak"

        // Гистограмма яркости и градиент по рамке.
        val hist = IntArray(256)
        var sum = 0L
        var gradSum = 0.0
        var dark = 0
        val pixels = bw * bh
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val idx = y * width + x
                val v = gray[idx].coerceIn(0, 255)
                hist[v]++
                sum += v
                if (x > 0 && y > 0 && x < maxX && y < maxY) {
                    val gx = 2 * gray[idx + 1] + gray[idx - width + 1] + gray[idx + width + 1] -
                        2 * gray[idx - 1] - gray[idx - width - 1] - gray[idx + width - 1]
                    val gy = 2 * gray[idx + width] + gray[idx + width - 1] + gray[idx + width + 1] -
                        2 * gray[idx - width] - gray[idx - width - 1] - gray[idx - width + 1]
                    gradSum += hypot(gx.toDouble(), gy.toDouble())
                }
                // Пиксель заметно темнее окрестности 9×9 — впадина дефекта.
                val w = min(maxX, x + 4) - max(0, x - 4) + 1
                val h = min(maxY, y + 4) - max(0, y - 4) + 1
                val area = w * h
                val localMean = (
                    integral[(min(maxY, y + 4) + 1) * (width + 1) + min(maxX, x + 4) + 1] -
                        integral[max(0, y - 4) * (width + 1) + min(maxX, x + 4) + 1] -
                        integral[(min(maxY, y + 4) + 1) * (width + 1) + max(0, x - 4)] +
                        integral[max(0, y - 4) * (width + 1) + max(0, x - 4)]
                    ) / area
                if (v - localMean < DARK_VS_NEIGHBORHOOD) dark++
                x++
            }
            y++
        }

        // Медиана яркости рамки.
        var median = 0
        var acc = 0
        while (median < 255) {
            acc += hist[median]
            if (acc * 2 >= pixels) break
            median++
        }
        var bright = 0
        val brightFrom = min(256, median + BRIGHT_OVER_MEDIAN)
        var i = brightFrom
        while (i < 256) {
            bright += hist[i]
            i++
        }
        val mean = sum.toDouble() / pixels
        val texture = (gradSum / pixels) / (mean + GRADIENT_MEAN_BIAS)

        // Линейные дефекты на светлом фоне — разметка или граница тени.
        if (isLinear(type) && bright.toFloat() / pixels > BRIGHT_BAND_FRACTION) return "marking/shadow-edge"
        if (texture < MIN_TEXTURE && confidence < TEXTURE_CONFIDENCE_FLOOR) return "no-texture"
        if (isLinear(type) &&
            dark.toFloat() / pixels < DARK_FRACTION_FLOOR &&
            texture < BAND_MAX_TEXTURE &&
            pixels.toFloat() / (width * height) > BAND_MIN_AREA_FRACTION
        ) {
            return "uniform-band"
        }
        return null
    }

    /** Серая карта как в эталоне: 0.3·R + 0.6·G + 0.1·B. */
    fun grayscale(argb: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        for (i in out.indices) {
            val p = argb[i]
            out[i] = (((p shr 16 and 0xFF) * 3 + (p shr 8 and 0xFF) * 6 + (p and 0xFF)) / 10)
        }
        return out
    }

    /** Интегральная карта яркости: (h+1)×(w+1), нулевая рамка сверху и слева. */
    fun integral(gray: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0
            for (x in 0 until width) {
                rowSum += gray[y * width + x]
                out[(y + 1) * (width + 1) + x + 1] = out[y * (width + 1) + x + 1] + rowSum
            }
        }
        return out
    }

    /** Линейные/полосные дефекты, путаемые с разметкой. */
    private fun isLinear(type: DefectType): Boolean =
        type == DefectType.CRACK_NETWORK || type == DefectType.RUTTING || type == DefectType.PATCH

    private fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))

    // Пороги выверены по эталонному fpCheck (RoadScan v5.8).
    private const val OFFROAD_MIN_ROAD_FRACTION = 0.05f
    private const val WEAK_POTHOLE_CONFIDENCE = 0.5f
    private const val DARK_VS_NEIGHBORHOOD = -12
    private const val BRIGHT_OVER_MEDIAN = 46
    private const val BRIGHT_BAND_FRACTION = 0.15f
    private const val GRADIENT_MEAN_BIAS = 20.0
    private const val MIN_TEXTURE = 0.058
    private const val TEXTURE_CONFIDENCE_FLOOR = 0.3f
    private const val DARK_FRACTION_FLOOR = 0.005f
    private const val BAND_MAX_TEXTURE = 0.09
    private const val BAND_MIN_AREA_FRACTION = 0.02f
}
