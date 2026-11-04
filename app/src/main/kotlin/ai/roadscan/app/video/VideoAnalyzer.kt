package ai.roadscan.app.video

import android.graphics.Bitmap
import ai.roadscan.core.model.FrameData
import ai.roadscan.core.model.VisionDetection
import ai.roadscan.core.video.ObservedDefect
import ai.roadscan.core.video.VideoAnalysis

/**
 * Проход нейросети по сырому видео: кадры сэмплируются с частотой 2–3 Гц,
 * детектор возвращает рамки в долях кадра. GPS и акселерометр в видео нет,
 * поэтому это чисто визуальный контур — площади и смета считаются отдельно
 * по калибровке, если она есть.
 */
class VideoAnalyzer(
    private val detector: (FrameData) -> List<VisionDetection>,
    private val minConfidence: Float = 0.35f,
) {
    /**
     * @param onPhoto сохраняет снимок кадра и возвращает путь к файлу.
     *   Вызывается только когда найдена находка лучше предыдущей по типу.
     */
    fun analyze(
        decoder: VideoStreamDecoder,
        info: VideoInfo,
        isActive: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
        onPhoto: ((ObservedDefect, Bitmap) -> String?)? = null,
    ): VideoAnalysis {
        val sampleFps = if (info.durationMs <= 180_000) 3f else 2f
        val stepMs = (1_000f / sampleFps).toLong().coerceAtLeast(1)
        val samples = ((info.durationMs / stepMs) + 1).coerceAtLeast(1)

        val detections = mutableListOf<ObservedDefect>()
        val photos = mutableMapOf<Long, String>()
        // Лучший снимок на тип: перезаписываем, если уверенность выросла.
        val bestPhotoConfidence = mutableMapOf<ai.roadscan.core.model.DefectType, Float>()
        var index = 0L
        var time = 0L
        val startedAt = android.os.SystemClock.elapsedRealtime()
        while (time <= info.durationMs && index < MAX_SAMPLES) {
            if (!isActive()) break
            // Без seek на каждый сэмпл: декодер идёт вперёд по потоку.
            // Обратный seek к ключевому кадру каждые 333 мс превращал
            // 10-секундный ролик в минуты декодирования.
            val frame = decoder.advanceTo(time) ?: break
            val data = FrameData(
                timestampMs = time,
                location = null,
                cameraHeightM = 0f,
                cameraPitchDeg = 0f,
                imageWidth = frame.width,
                imageHeight = frame.height,
                speedKmh = 0f,
                meanContrast = 1f,
                sensorTimestampMs = time,
                imageRef = frame,
            )
            val found = detector(data).filter { it.confidence >= minConfidence }
            found.forEach { detections += it.toObserved(time) }
            val best = found.maxByOrNull { it.confidence }
            if (onPhoto != null && best != null) {
                val previous = bestPhotoConfidence[best.type] ?: 0f
                if (best.confidence > previous) {
                    onPhoto(best.toObserved(time), frame)?.let { path ->
                        photos[time] = path
                        bestPhotoConfidence[best.type] = best.confidence
                    }
                }
            }
            index++
            time += stepMs
            if (index % 5L == 0L) {
                val spent = android.os.SystemClock.elapsedRealtime() - startedAt
                android.util.Log.d(
                    "RoadScanVideo",
                    "анализ: $index/$samples сэмплов за $spent мс, дефектов ${detections.size}",
                )
            }
            onProgress((index.toFloat() / samples).coerceIn(0f, 1f))
        }

        return VideoAnalysis(
            durationMs = info.durationMs,
            width = info.width,
            height = info.height,
            sampledFps = sampleFps,
            detections = detections,
            photos = photos,
        )
    }

    private fun VisionDetection.toObserved(timeMs: Long) = ObservedDefect(
        timeMs = timeMs,
        type = type,
        confidence = confidence,
        box = box,
    )

    private companion object {
        const val MAX_SAMPLES = 1_200
    }
}
