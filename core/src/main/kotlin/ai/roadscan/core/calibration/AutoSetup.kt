package ai.roadscan.core.calibration

import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Автоподготовка комплекта: оптика камеры и профиль устройства.
 *
 * Оператору не нужно ничего измерять линейкой и подбирать руками:
 *  - фокусное расстояние в пикселях берётся из характеристик камеры
 *    (фокус в мм и размер сенсора — то, что сообщает Android);
 *  - наклон камеры оценивается по датчику тяжотения, когда телефон уже
 *    стоит в кронштейне;
 *  - профиль производительности (потоки, частота анализа, разрешение)
 *    подбирается по ядрам, памяти и измеренной задержке инференса.
 *
 * Всё считается из простых формул — покрыто тестами, без Android.
 */
object CameraOptics {

    /**
     * Фокусное расстояние в пикселях для кадра [imageWidthPx].
     *
     * [focalLengthMm] — фокус объектива (Android: LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
     * [sensorWidthMm] — ширина сенсора (SENSOR_INFO_PHYSICAL_SIZE).
     */
    fun focalLengthPx(focalLengthMm: Float, sensorWidthMm: Float, imageWidthPx: Int): Float {
        require(focalLengthMm > 0f) { "фокус должен быть положительным" }
        require(sensorWidthMm > 0f) { "сенсор должен быть положительным" }
        require(imageWidthPx > 0) { "кадр должен быть непустым" }
        return focalLengthMm / sensorWidthMm * imageWidthPx
    }

    /**
     * Наклон камеры вниз, градусы, по вектору тяжотения в координатах
     * устройства (м/с², покой ≈ 9.8).
     *
     * Модель: телефон вертикально в кронштейне экраном к оператору, камера
     * на спине смотрит вдоль дороги. Ось камеры — «минус Z» устройства,
     * поэтому наклон оптической оси под горизонт — это та часть тяжотения,
     * что ложится на ось Z:
     *
     *   pitch = asin(-g_z / |g|)
     *
     * Проверка: телефон ровно вертикально (g=(0,-9.8,0)) → pitch = 0,
     * камера смотрит по горизонту; завал «на спину» на θ → pitch = θ,
     * камера смотрит вниз. Результат ограничивается диапазоном профиля.
     */
    fun pitchDegFromGravity(gx: Float, gy: Float, gz: Float): Float {
        val magnitude = sqrt(gx * gx + gy * gy + gz * gz)
        if (magnitude < 1e-3f) return Float.NaN
        val deg = Math.toDegrees(asin((-gz / magnitude).toDouble())).toFloat()
        return deg.coerceIn(-5f, 45f)
    }
}

/**
 * Рекомендуемый профиль работы на конкретном устройстве.
 */
data class DeviceTuning(
    /** Потоки инференса. */
    val inferenceThreads: Int,
    /** Целевая частота анализа кадров, FPS. */
    val analysisFps: Int,
    /** Ширина кадра анализа (меньше — быстрее, ближе — точнее площадь). */
    val analysisWidthPx: Int,
) {
    init {
        require(inferenceThreads >= 1) { "минимум один поток" }
        require(analysisFps >= 6) { "минимальная частота анализа — 6 FPS" }
        require(analysisWidthPx >= 640) { "уже 640 px площадь не считается" }
    }
}

object DeviceTuner {

    /**
     * Подбор профиля по железу и замеренной задержке инференса.
     *
     * Правила:
     *  - потоки: половина ядер, но не более 4 — на big.LITTLE лишние потоки
     *    только конкурируют за малые ядра;
     *  - FPS и разрешение — по бюджету кадра ТЗ (45 мс): если инференс
     *    влезает в бюджет, работаем в полном разрешении, иначе поэтапно
     *    снижаем нагрузку.
     */
    fun recommend(
        cpuCores: Int,
        ramMb: Int,
        inferenceMs: Double,
    ): DeviceTuning {
        val cores = cpuCores.coerceAtLeast(1)
        val threads = (cores / 2).coerceIn(1, 4)

        // Слабое железо (< 3 ГБ) бережём даже при быстром инференсе.
        val weakDevice = ramMb in 1 until 3000
        return when {
            inferenceMs <= 45.0 && !weakDevice -> DeviceTuning(threads, 30, 1280)
            inferenceMs <= 90.0 -> DeviceTuning(threads, 15, 960)
            inferenceMs <= 200.0 -> DeviceTuning(threads, 10, 960)
            else -> DeviceTuning(threads, 6, 640)
        }
    }
}
