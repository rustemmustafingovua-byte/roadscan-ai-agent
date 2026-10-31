package ai.roadscan.app.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import ai.roadscan.core.agent.VisionDetector
import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.FrameData
import ai.roadscan.core.model.VisionDetection
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * TFLite YOLOv8-детектор.
 *
 * Модель кладётся в `app/src/main/assets/roadscan_yolov8.tflite`
 * (экспорт YOLOv8n/s с 5 классами: pothole, crack_network, rutting, patch,
 * manhole_cover, вход 640×640 float32). Если файла нет — детектор возвращает
 * пустой список, приложение работает в режиме «только телеметрия».
 */
class YoloV8Detector(
    context: Context,
    private val assetName: String = "roadscan_yolov8.tflite",
    private val confidenceThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f,
    private val inputSize: Int = 640,
) : VisionDetector, Closeable {

    private val interpreter: Interpreter? = runCatching {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply { put(bytes); rewind() }
        Interpreter(
            model,
            Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            },
        )
    }.getOrNull()

    private val labels = runCatching {
        context.assets.open("labels.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
    }.getOrElse { DEFAULT_LABELS }

    val isReady: Boolean get() = interpreter != null

    override fun detect(frame: FrameData): List<VisionDetection> {
        val interp = interpreter ?: return emptyList()
        val bitmap = frame.imageRef as? Bitmap ?: return emptyList()

        return runCatching {
            val letterbox = Letterbox.create(bitmap, inputSize)
            try {
                val input = toFloatBuffer(letterbox.bitmap)
                val outputShape = interp.getOutputTensor(0).shape()
                val channelsFirst = outputShape.size == 3 && outputShape[1] < outputShape[2]

                val channels = if (channelsFirst) outputShape[1] else outputShape[2]
                val candidates = if (channelsFirst) outputShape[2] else outputShape[1]
                val output = if (channelsFirst) {
                    Array(1) { Array(channels) { FloatArray(candidates) } }
                } else {
                    Array(1) { Array(candidates) { FloatArray(channels) } }
                }
                interp.run(input, output)

                val detections = mutableListOf<VisionDetection>()
                val classCount = channels - 4
                for (i in 0 until candidates) {
                    var bestScore = 0f
                    var bestClass = -1
                    for (c in 0 until classCount) {
                        val score = if (channelsFirst) output[0][4 + c][i] else output[0][i][4 + c]
                        if (score > bestScore) {
                            bestScore = score
                            bestClass = c
                        }
                    }
                    if (bestScore < confidenceThreshold || bestClass < 0) continue

                    val cx = value(output, channelsFirst, i, 0)
                    val cy = value(output, channelsFirst, i, 1)
                    val w = value(output, channelsFirst, i, 2)
                    val h = value(output, channelsFirst, i, 3)

                    val box = letterbox.toFrameBox(cx, cy, w, h, bitmap.width, bitmap.height)
                    detections += VisionDetection(
                        type = labelToType(labels.getOrNull(bestClass)),
                        confidence = bestScore,
                        box = box,
                    )
                }
                nms(detections)
            } finally {
                letterbox.bitmap.recycle()
            }
        }.getOrElse { emptyList() }
    }

    private fun value(output: Array<Array<FloatArray>>, channelsFirst: Boolean, i: Int, c: Int): Float {
        val raw = if (channelsFirst) output[0][c][i] else output[0][i][c]
        // Некоторые экспорты отдают координаты в пикселях, некоторые — нормализованные.
        return if (raw <= 2f) raw * inputSize else raw
    }

    private fun toFloatBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private fun nms(detections: List<VisionDetection>): List<VisionDetection> {
        val result = mutableListOf<VisionDetection>()
        for (type in detections.groupBy { it.type }.keys) {
            val sorted = detections.filter { it.type == type }.sortedByDescending { it.confidence }
            val kept = mutableListOf<VisionDetection>()
            for (candidate in sorted) {
                if (kept.none { iou(it.box, candidate.box) > iouThreshold }) kept += candidate
            }
            result += kept
        }
        return result.sortedByDescending { it.confidence }
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

    override fun close() {
        interpreter?.close()
    }

    private class Letterbox(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float,
    ) {
        fun toFrameBox(cx: Float, cy: Float, w: Float, h: Float, frameW: Int, frameH: Int): BoundingBox {
            val left = ((cx - w / 2f) - padX) / scale / frameW
            val top = ((cy - h / 2f) - padY) / scale / frameH
            val right = ((cx + w / 2f) - padX) / scale / frameW
            val bottom = ((cy + h / 2f) - padY) / scale / frameH
            return BoundingBox(
                left = left.coerceIn(0f, 1f),
                top = top.coerceIn(0f, 1f),
                right = right.coerceIn(0f, 1f),
                bottom = bottom.coerceIn(0f, 1f),
            )
        }

        companion object {
            fun create(source: Bitmap, size: Int): Letterbox {
                val scale = min(size.toFloat() / source.width, size.toFloat() / source.height)
                val scaledW = (source.width * scale).toInt().coerceAtLeast(1)
                val scaledH = (source.height * scale).toInt().coerceAtLeast(1)
                val padX = (size - scaledW) / 2f
                val padY = (size - scaledH) / 2f
                val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                canvas.drawColor(Color.BLACK)
                val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
                canvas.drawBitmap(scaled, padX, padY, null)
                if (scaled !== source) scaled.recycle()
                return Letterbox(output, scale, padX, padY)
            }
        }
    }

    private fun labelToType(label: String?): DefectType = when (label?.lowercase()) {
        "pothole", "яма" -> DefectType.POTHOLE
        "crack_network", "crack", "трещина" -> DefectType.CRACK_NETWORK
        "rutting", "колея" -> DefectType.RUTTING
        "patch", "заплатка" -> DefectType.PATCH
        "manhole_cover", "manhole", "люк" -> DefectType.MANHOLE_COVER
        else -> DefectType.UNKNOWN
    }

    companion object {
        private val DEFAULT_LABELS = listOf(
            "pothole",
            "crack_network",
            "rutting",
            "patch",
            "manhole_cover",
        )
    }
}
