package ai.roadscan.app.perception

import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.VisionDetection
import kotlin.math.max
import kotlin.math.min

/**
 * Наложение результатов детекции на живой кадр (HUD).
 *
 * Модель отдаёт дефекты покадрово, а оператору нужны стабильные подписи
 * «P-042 · 98.6%»: без трекера идентификатор менялся бы 30 раз в секунду и
 * подпись мигала. Трекер сопоставляет рамки соседних кадров по типу и IoU и
 * переиспользует номер, пока дефект виден.
 */
data class OverlayDetection(
    val id: String,
    val type: DefectType,
    val confidence: Float,
    val box: BoundingBox,
)

class DetectionTracker(
    /** Сколько кадров/мс помнить дефект, ушедший из вида. */
    private val ttlMs: Long = 1_200,
    private val iouThreshold: Float = 0.35f,
) {
    private data class Tracked(
        val id: String,
        val type: DefectType,
        var box: BoundingBox,
        var confidence: Float,
        var lastSeenMs: Long,
    )

    private val tracked = mutableListOf<Tracked>()
    private val counters = mutableMapOf<DefectType, Int>()

    @Synchronized
    fun update(detections: List<VisionDetection>, nowMs: Long): List<OverlayDetection> {
        tracked.removeAll { nowMs - it.lastSeenMs > ttlMs }

        val result = mutableListOf<OverlayDetection>()
        for (detection in detections) {
            val match = tracked
                .filter { it.type == detection.type }
                .maxByOrNull { iou(it.box, detection.box) }
                ?.takeIf { iou(it.box, detection.box) >= iouThreshold }

            val item = if (match != null) {
                match.apply {
                    box = detection.box
                    confidence = detection.confidence
                    lastSeenMs = nowMs
                }
            } else {
                val number = (counters[detection.type] ?: 0) + 1
                counters[detection.type] = number
                Tracked(
                    id = "%s-%03d".format(prefix(detection.type), number),
                    type = detection.type,
                    box = detection.box,
                    confidence = detection.confidence,
                    lastSeenMs = nowMs,
                ).also { tracked += it }
            }

            result += OverlayDetection(
                id = item.id,
                type = item.type,
                confidence = item.confidence,
                box = item.box,
            )
        }
        return result
    }

    @Synchronized
    fun reset() {
        tracked.clear()
        counters.clear()
    }

    private fun prefix(type: DefectType): String = when (type) {
        DefectType.POTHOLE -> "P"
        DefectType.CRACK_NETWORK -> "C"
        DefectType.RUTTING -> "R"
        DefectType.PATCH -> "S"
        DefectType.MANHOLE_COVER -> "M"
        DefectType.UNKNOWN -> "X"
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
