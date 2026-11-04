package ai.roadscan.app.perception

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import ai.roadscan.core.agent.RoadScanAgent
import ai.roadscan.core.calibration.CalibrationProfile
import ai.roadscan.core.model.FrameData
import ai.roadscan.core.model.GeoPoint
import ai.roadscan.core.model.VisionDetection
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** Результат обработки одного кадра — для HUD и метрик. */
data class FrameMetrics(
    val latencyMs: Double,
    val contrast: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val overlays: List<OverlayDetection>,
    val speedKmh: Float,
)

/**
 * CameraX-анализатор: кадр -> контраст -> YOLO -> агент -> наложение для HUD.
 *
 * Агент и калибровка берутся через провайдеры: пользователь может изменить
 * профиль автомобиля и пересобрать агента без пересоздания анализатора.
 * Работает на одном фоновом executor'е, поэтому порядок кадров сохраняется.
 */
class FrameAnalyzer(
    private val agentProvider: () -> RoadScanAgent,
    private val calibrationProvider: () -> CalibrationProfile,
    private val locationProvider: () -> GeoPoint?,
    private val speedProvider: () -> Float,
    /** Дефекты последнего кадра из детектора (для подписей на превью). */
    private val detectionsProvider: () -> List<VisionDetection> = { emptyList() },
    /**
     * Включён ли vision-контур. В фоновом режиме (низкий заряд) кадры
     * отбрасываются до конвертации в Bitmap — это и есть экономия энергии.
     */
    private val visionEnabledProvider: () -> Boolean = { true },
    /** Снимок находки в момент обнаружения (для ведомости). */
    private val onDefectPhoto: ((String, Bitmap) -> Unit)? = null,
    /** Задержка последнего инференса, мс (для журнала производительности). */
    private val detectorLatencyMsProvider: () -> Double = { 0.0 },
    /** Активный бэкенд инференса (NNAPI/XNNPACK) — для журнала. */
    private val backendProvider: () -> String = { "?" },
    private val onFrame: (FrameMetrics) -> Unit = {},
) : ImageAnalysis.Analyzer {

    val framesSeen = AtomicLong(0)
    private val tracker = DetectionTracker()

    override fun analyze(image: ImageProxy) {
        val started = System.nanoTime()
        var original: Bitmap? = null
        var oriented: Bitmap? = null
        try {
            if (!visionEnabledProvider()) {
                // Фоновый режим: кадр не анализируется. finally всё равно
                // закроет ImageProxy — камера не встанет.
                return
            }
            original = image.toBitmap()
            oriented = rotate(original, image.imageInfo.rotationDegrees)
            val contrast = meanContrast(oriented)
            val calibration = calibrationProvider()
            val speed = speedProvider()

            val frame = FrameData(
                timestampMs = System.currentTimeMillis(),
                location = locationProvider(),
                cameraHeightM = calibration.cameraHeightM,
                cameraPitchDeg = calibration.cameraPitchDeg,
                imageWidth = oriented.width,
                imageHeight = oriented.height,
                speedKmh = speed,
                meanContrast = contrast,
                // Метка момента съёмки, а не обработки: окно фузии ±50 мс должно
                // совпадать с ударом в момент съёмки кадра, иначе при 90 км/ч
                // удар смещается за пределы окна. CameraX отдаёт время в шкале
                // elapsedRealtime — той же, что и SensorEvent.timestamp.
                sensorTimestampMs = captureTimestampMs(image),
                imageRef = oriented,
            )
            val accepted = agentProvider().evaluateFrameAndSensor(frame)
            // Снимок берём сразу: следующий кадр перезапишет картинку.
            if (accepted != null) {
                onDefectPhoto?.invoke(accepted.id, oriented)
            }
            val seen = framesSeen.incrementAndGet()
            // Раз в секунду — куда уходит кадровый бюджет: полная обработка
            // против одного инференса. Без этого оптимизация вслепую.
            if (seen % LOG_EVERY_N_FRAMES == 0L) {
                android.util.Log.d(
                    "RoadScanPerf",
                    "frame=$seen total=%.1fms infer=%.1fms backend=%s size=%dx%d".format(
                        (System.nanoTime() - started) / 1_000_000.0,
                        detectorLatencyMsProvider(),
                        backendProvider(),
                        oriented.width,
                        oriented.height,
                    ),
                )
            }

            val now = SystemClock.elapsedRealtime()
            val overlays = tracker.update(detectionsProvider(), now)
            onFrame(
                FrameMetrics(
                    latencyMs = (System.nanoTime() - started) / 1_000_000.0,
                    contrast = contrast,
                    frameWidth = oriented.width,
                    frameHeight = oriented.height,
                    overlays = overlays,
                    speedKmh = speed,
                ),
            )
        } catch (_: Throwable) {
            // Один битый кадр не должен ронять смену.
        } finally {
            if (oriented != null && oriented !== original) oriented.recycle()
            original?.recycle()
            image.close()
        }
    }

    private companion object {
        /** Как часто писать строку производительности в лог (кадров). */
        const val LOG_EVERY_N_FRAMES = 30L
    }

    /** Сброс трекера на старте смены: номера дефектов начинаются с 001. */
    fun resetTracking() = tracker.reset()

    /**
     * Метка времени съёмки кадра в шкале elapsedRealtime, мс.
     * Если производитель не отдал метку (0), берём текущее время.
     */
    private fun captureTimestampMs(image: ImageProxy): Long {
        val nanos = runCatching { image.imageInfo.timestamp }.getOrDefault(0L)
        return if (nanos > 0L) nanos / 1_000_000L else SystemClock.elapsedRealtime()
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Среднеквадратичное отклонение яркости, нормированное на 128.
     * Грязь/капли дают почти однородный кадр -> контраст < 0.12.
     */
    private fun meanContrast(bitmap: Bitmap): Float {
        val stepX = (bitmap.width / 64).coerceAtLeast(1)
        val stepY = (bitmap.height / 64).coerceAtLeast(1)
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luma = (0.299 * ((pixel shr 16) and 0xFF) +
                    0.587 * ((pixel shr 8) and 0xFF) +
                    0.114 * (pixel and 0xFF)) / 255.0
                sum += luma
                sumSq += luma * luma
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return 0f
        val mean = sum / count
        val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
        return (sqrt(variance)).toFloat().coerceIn(0f, 1f)
    }
}
