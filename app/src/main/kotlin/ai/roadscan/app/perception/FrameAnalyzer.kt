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
    private val onFrame: (FrameMetrics) -> Unit = {},
) : ImageAnalysis.Analyzer {

    val framesSeen = AtomicLong(0)
    private val tracker = DetectionTracker()

    override fun analyze(image: ImageProxy) {
        val started = System.nanoTime()
        var original: Bitmap? = null
        var oriented: Bitmap? = null
        try {
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
                sensorTimestampMs = SystemClock.elapsedRealtime(),
                imageRef = oriented,
            )
            agentProvider().evaluateFrameAndSensor(frame)
            framesSeen.incrementAndGet()

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

    /** Сброс трекера на старте смены: номера дефектов начинаются с 001. */
    fun resetTracking() = tracker.reset()

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
