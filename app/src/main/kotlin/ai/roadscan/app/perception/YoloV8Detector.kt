package ai.roadscan.app.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import ai.roadscan.core.agent.VisionDetector
import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.FrameData
import ai.roadscan.core.model.VisionDetection
import android.os.Build
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
 * (YOLO11n на RDD2022, 6 классов, вход 640×640 float32, выход [1,10,8400]).
 * Если файла нет — детектор возвращает пустой список, приложение работает в
 * режиме «только телеметрия».
 *
 * Производительность (цель ТЗ — кадр < 45 мс):
 *  - все буферы (вход, выход, пиксели, letterbox-канвас) выделяются один раз
 *    и переиспользуются, иначе GC съедает кадровый бюджет;
 *  - масштабирование делается напрямую canvas'ом в letterbox, без временного
 *    scaled Bitmap;
 *  - детектор вызывается из одного потока анализа (CameraX executor),
 *    поэтому общие буферы не требуют блокировок.
 */
class YoloV8Detector(
    context: Context,
    private val assetName: String = "roadscan_yolov8.tflite",
    private val confidenceThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f,
    /**
     * Размер входа модели; null -> берём из тензора (тогда смена модели
     * не требует правки кода).
     */
    inputSize: Int? = null,
    numThreads: Int = 4,
) : VisionDetector, Closeable {

    /** Чем реально считает интерпретатор. */
    enum class Backend { NNAPI, XNNPACK }

    private val modelBytes: ByteBuffer? = runCatching {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply { put(bytes); rewind() }
    }.getOrNull()

    private var interpreter: Interpreter? = null

    /** Активен ли NNAPI-делегат (иначе считаем через XNNPACK). */
    private var nnapiActive = false

    /** Активный бэкенд инференса — для журнала и диагностики в UI. */
    var backend: Backend = Backend.XNNPACK
        private set

    /**
     * Разрешение входа модели (по умолчанию — из её тензора).
     * Меняется только при пересоздании интерпретатора, поэтому val.
     */
    val resolvedInputSize: Int

    // --- переиспользуемые буферы ---
    private val inputBuffer: ByteBuffer
    private val pixels: IntArray
    private val letterboxBitmap: Bitmap
    private val canvas: Canvas
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    init {
        interpreter = createInterpreter(preferNnapi = true)
        backend = if (interpreter != null && nnapiActive) Backend.NNAPI else Backend.XNNPACK
        val size = inputSize ?: detectInputSize(interpreter)
        resolvedInputSize = size
        inputBuffer = ByteBuffer
            .allocateDirect(1 * size * size * 3 * 4)
            .order(ByteOrder.nativeOrder())
        pixels = IntArray(size * size)
        letterboxBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        canvas = Canvas(letterboxBitmap)
    }

    /** Разрешение входа модели по её тензору. */
    private fun detectInputSize(interp: Interpreter?): Int {
        val shape = runCatching { interp?.getInputTensor(0)?.shape() }.getOrNull() ?: return DEFAULT_INPUT_SIZE
        if (shape.size != 4) return DEFAULT_INPUT_SIZE
        // NCHW: [1, 3, H, W]; NHWC: [1, H, W, 3].
        val candidate = when {
            shape[1] == 3 -> shape[2]
            shape[3] == 3 -> shape[1]
            else -> shape[2]
        }
        return if (candidate in 64..2048) candidate else DEFAULT_INPUT_SIZE
    }

    /**
     * NNAPI (аппаратное ускорение: DSP/GPU/NPU) заметно быстрее XNNPACK, но
     * драйверы бывают капризными: часть устройств отдаёт NNAPI-софтверный
     * fallback, который медленнее XNNPACK. Поэтому после нескольких кадров
     * замеряем реальную скорость и при необходимости возвращаемся на XNNPACK.
     */
    private var inferencesWithBackend = 0
    private var latencySumMs = 0.0

    private fun createInterpreter(preferNnapi: Boolean): Interpreter? {
        val model = modelBytes ?: return null
        if (preferNnapi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                Interpreter(
                    model,
                    Interpreter.Options().apply {
                        setNumThreads(numThreads)
                        setUseXNNPACK(true)
                        setUseNNAPI(true)
                    },
                )
            }.getOrNull()?.let {
                nnapiActive = true
                return it
            }
        }
        nnapiActive = false
        return runCatching {
            Interpreter(
                model,
                Interpreter.Options().apply {
                    setNumThreads(numThreads)
                    setUseXNNPACK(true)
                },
            )
        }.getOrNull()
    }

    /** Возврат к XNNPACK, если выбранный бэкенд не тянет кадровый бюджет. */
    private fun considerDowngrade(elapsedMs: Double) {
        if (!nnapiActive) return
        inferencesWithBackend++
        latencySumMs += elapsedMs
        // Катастрофически медленный кадр — откатываемся сразу, не ждя выборки.
        val average = latencySumMs / inferencesWithBackend
        if (elapsedMs <= CATASTROPHIC_INFERENCE_MS && inferencesWithBackend < 10) return
        if (average <= SLOW_INFERENCE_MS && elapsedMs <= CATASTROPHIC_INFERENCE_MS) return
        runCatching { interpreter?.close() }
        interpreter = createInterpreter(preferNnapi = false)
        nnapiActive = false
        backend = Backend.XNNPACK
        inferencesWithBackend = 0
        latencySumMs = 0.0
        // Буфер выхода пересоздаём под новый интерпретатор.
        output = null
    }

    private val labels = runCatching {
        context.assets.open("labels.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
    }.getOrElse { DEFAULT_LABELS }

    val isReady: Boolean get() = interpreter != null

    /** Признак того, что модель ожидает NCHW (обычно NHWC). */
    private var channelsFirst: Boolean? = null


    private var output: Array<Array<FloatArray>>? = null
    private var outputChannels = 0
    private var outputCandidates = 0
    private var outputChannelsFirst = true

    /** Последняя измеренная задержка инференса, мс (для журнала и UI). */
    @Volatile
    var lastLatencyMs: Double = 0.0
        private set

    /** Дефекты последнего кадра — для HUD-наложения поверх превью. */
    @Volatile
    var lastDetections: List<VisionDetection> = emptyList()
        private set

    override fun detect(frame: FrameData): List<VisionDetection> {
        val interp = interpreter ?: return emptyList()
        val bitmap = frame.imageRef as? Bitmap ?: return emptyList()

        val startedNs = System.nanoTime()
        return runCatching {
            prepareOutput(interp)
            drawLetterbox(bitmap)
            fillInput()

            val boxes = output ?: return emptyList()
            interp.run(inputBuffer, boxes)

            val detections = mutableListOf<VisionDetection>()
            val classCount = outputChannels - 4
            val normalized = coordinatesNormalized(boxes, outputCandidates)
            val scale = min(resolvedInputSize.toFloat() / bitmap.width, resolvedInputSize.toFloat() / bitmap.height)
            val padX = (resolvedInputSize - bitmap.width * scale) / 2f
            val padY = (resolvedInputSize - bitmap.height * scale) / 2f

            for (i in 0 until outputCandidates) {
                var bestScore = 0f
                var bestClass = -1
                for (c in 0 until classCount) {
                    val score = if (outputChannelsFirst) boxes[0][4 + c][i] else boxes[0][i][4 + c]
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = c
                    }
                }
                if (bestScore < confidenceThreshold || bestClass < 0) continue

                val type = labelToType(labels.getOrNull(bestClass))
                // Служебные классы разметки (блики перехода/разметки) не являются дефектами.
                if (type == DefectType.UNKNOWN) continue

                val cx = coordinate(boxes, i, 0, normalized)
                val cy = coordinate(boxes, i, 1, normalized)
                val w = coordinate(boxes, i, 2, normalized)
                val h = coordinate(boxes, i, 3, normalized)

                val box = toFrameBox(cx, cy, w, h, scale, padX, padY, bitmap.width, bitmap.height)
                if (box.width < 0.01f || box.height < 0.01f) continue
                detections += VisionDetection(type = type, confidence = bestScore, box = box)
            }
            nms(detections)
        }.getOrElse { emptyList() }.also {
            lastLatencyMs = (System.nanoTime() - startedNs) / 1_000_000.0
            lastDetections = it
            considerDowngrade(lastLatencyMs)
        }
    }

    private fun prepareOutput(interp: Interpreter) {
        val cached = output
        if (cached != null) return
        val shape = interp.getOutputTensor(0).shape()
        val first = shape.size == 3 && shape[1] < shape[2]
        outputChannelsFirst = first
        outputChannels = if (first) shape[1] else shape[2]
        outputCandidates = if (first) shape[2] else shape[1]
        output = if (first) {
            Array(1) { Array(outputChannels) { FloatArray(outputCandidates) } }
        } else {
            Array(1) { Array(outputCandidates) { FloatArray(outputChannels) } }
        }
        channelsFirst = interp.getInputTensor(0).shape().let { it.size == 4 && it[1] == 3 }
    }

    private fun drawLetterbox(source: Bitmap) {
        val scale = min(resolvedInputSize.toFloat() / source.width, resolvedInputSize.toFloat() / source.height)
        val scaledW = source.width * scale
        val scaledH = source.height * scale
        val padX = (resolvedInputSize - scaledW) / 2f
        val padY = (resolvedInputSize - scaledH) / 2f
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, null, RectF(padX, padY, padX + scaledW, padY + scaledH), paint)
    }

    private fun fillInput() {
        letterboxBitmap.getPixels(pixels, 0, resolvedInputSize, 0, 0, resolvedInputSize, resolvedInputSize)
        inputBuffer.rewind()
        if (channelsFirst == true) {
            for (channel in 0..2) {
                val shift = 16 - channel * 8
                for (pixel in pixels) {
                    inputBuffer.putFloat(((pixel shr shift) and 0xFF) / 255f)
                }
            }
        } else {
            for (pixel in pixels) {
                inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                inputBuffer.putFloat((pixel and 0xFF) / 255f)
            }
        }
        inputBuffer.rewind()
    }

    private fun coordinate(
        output: Array<Array<FloatArray>>,
        i: Int,
        c: Int,
        normalized: Boolean,
    ): Float {
        val raw = if (outputChannelsFirst) output[0][c][i] else output[0][i][c]
        return if (normalized) raw * resolvedInputSize else raw
    }

    /**
     * Экспорт YOLOv8/11 в TFLite отдаёт координаты либо в пикселях входного
     * тензора, либо нормализованными 0..1. Определяем по максимуму координат,
     * а не по каждой рамке (иначе маленькие объекты «уезжают» в 640 раз).
     */
    private fun coordinatesNormalized(
        output: Array<Array<FloatArray>>,
        candidates: Int,
    ): Boolean {
        var max = 0f
        var i = 0
        while (i < candidates) {
            for (c in 0..3) {
                val raw = if (outputChannelsFirst) output[0][c][i] else output[0][i][c]
                if (raw > max) max = raw
            }
            i += 7
        }
        return max <= 2f
    }

    private fun toFrameBox(
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        scale: Float,
        padX: Float,
        padY: Float,
        frameW: Int,
        frameH: Int,
    ): BoundingBox {
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

    /**
     * Маппинг классов модели на дефекты приложения.
     *
     * Модель YOLOv8m «Road Anomaly» выдаёт 7 классов: Heavy-Vehicle,
     * Light-Vehicle, Pedestrian, Crack, Crack-Severe, Pothole, Speed-Bump.
     * Транспорт, пешеходы и лежачие полигоны — не дефекты покрытия, они
     * отбрасываются (UNKNOWN). Трещины любой тяжести идут в один класс
     * «сеть трещин»: различение по критичности делает SeverityScorer.
     */
    private fun labelToType(label: String?): DefectType = when (label?.lowercase()?.trim()) {
        "pothole", "potholes", "яма" -> DefectType.POTHOLE
        "crack", "crack-severe", "crack_severe", "crack network", "трещина",
        "alligator", "longitudinal", "equal interval", "transverse" -> DefectType.CRACK_NETWORK
        "rutting", "колея" -> DefectType.RUTTING
        "patch", "заплатка" -> DefectType.PATCH
        "manhole_cover", "manhole", "sewage-manhole", "люк" -> DefectType.MANHOLE_COVER
        else -> DefectType.UNKNOWN
    }

    companion object {
        /** Разрешение входа, если модель не сообщила своё. */
        private const val DEFAULT_INPUT_SIZE = 640

        /**
         * Порог, после которого NNAPI считается непригодным и мы возвращаемся
         * на XNNPACK. Цель ТЗ — кадр < 45 мс; 80 мс — заведомо вне бюджета.
         */
        private const val SLOW_INFERENCE_MS = 80.0

        /** Один кадр дольше этого — точно не NNAPI-конфигурация, откатываемся. */
        private const val CATASTROPHIC_INFERENCE_MS = 250.0

        private val DEFAULT_LABELS = listOf(
            "Heavy-Vehicle",
            "Light-Vehicle",
            "Pedestrian",
            "Crack",
            "Crack-Severe",
            "Pothole",
            "Speed-Bump",
        )
    }
}
