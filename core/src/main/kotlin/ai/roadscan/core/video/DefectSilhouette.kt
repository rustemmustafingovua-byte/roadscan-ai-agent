package ai.roadscan.core.video

import ai.roadscan.core.model.DefectType
import kotlin.math.abs
import kotlin.math.pow

/**
 * Силуэт дефекта внутри рамки детектора.
 *
 * Рамка YOLO — прямоугольник, а дефект на дороге имеет форму: яма —
 * тёмное пятно неправильного очертания, трещина — светлая линия. Чтобы
 * на видео было видно именно повреждение, а не абстрактный квадрат,
 * силуэт строится так же, как в эталонном RoadScan: суперэллипс как
 * предположение о форме + яркость пикселей внутри рамки.
 *
 *  - ямы, колеи, заплаты темнее полотна → берём пиксели темнее среднего;
 *  - трещины и «ромбовидные» — контрастные: берём пиксели, отличающиеся
 *    от среднего больше чем на четверть сигмы;
 *  - если текстуры почти нет (ровная рамка), силуэт вырождается в сам
 *    суперэллипс — рамка-подсказка лучше, чем пустота.
 *
 * Работает на малой сетке (обычно до 64 px по стороне) — дёшево и
 * детерминированно, покрыто тестами.
 */
object DefectSilhouette {

    /** Экспонента суперэллипса: 2.0 — эллипс, больше — «прямоугольнее». */
    private const val SUPERELLIPSE_EXP = 2.5

    /**
     * Маска силуэта: [width]×[height] булевых значений, true — пиксель
     * принадлежит дефекту. [luma] — яркость 0..255 той же сетки.
     */
    fun mask(
        luma: IntArray,
        width: Int,
        height: Int,
        type: DefectType,
    ): BooleanArray {
        require(luma.size == width * height) { "сетка не совпадает с размером" }
        val out = BooleanArray(width * height)
        if (width < 2 || height < 2) return out

        var sum = 0L
        var sumSq = 0L
        for (v in luma) {
            sum += v
            sumSq += v.toLong() * v
        }
        val n = luma.size
        val mean = sum.toDouble() / n
        val variance = (sumSq.toDouble() / n - mean * mean).coerceAtLeast(0.0)
        val std = kotlin.math.sqrt(variance)

        val contrastive = type == DefectType.CRACK_NETWORK
        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        val rx = width / 2f
        val ry = height / 2f

        var insideEllipse = 0
        var selected = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!inSuperellipse((x - cx) / rx, (y - cy) / ry)) continue
                insideEllipse++
                val v = luma[y * width + x]
                val hit = if (contrastive) {
                    abs(v - mean) > std * 0.5
                } else {
                    v < mean
                }
                if (hit) {
                    out[y * width + x] = true
                    selected++
                }
            }
        }

        // Текстуры нет — показываем хотя бы форму (суперэллипс целиком).
        if (insideEllipse > 0 && selected * 20 < insideEllipse) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[y * width + x] = inSuperellipse((x - cx) / rx, (y - cy) / ry)
                }
            }
        }
        return smooth(out, width, height)
    }

    /** Точка внутри суперэллипса с экспонентой [SUPERELLIPSE_EXP]. */
    private fun inSuperellipse(nx: Float, ny: Float): Boolean {
        val a = abs(nx).toDouble().pow(SUPERELLIPSE_EXP)
        val b = abs(ny).toDouble().pow(SUPERELLIPSE_EXP)
        return a + b <= 1.0
    }

    /** Простое сглаживание 3×3: убирает «соль» из маски. */
    private fun smooth(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var on = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height && mask[ny * width + nx]) on++
                    }
                }
                out[y * width + x] = on >= 5
            }
        }
        return out
    }
}
