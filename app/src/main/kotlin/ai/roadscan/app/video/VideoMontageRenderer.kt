package ai.roadscan.app.video

import ai.roadscan.core.model.DefectType
import ai.roadscan.core.video.MontageEvent
import ai.roadscan.core.video.MontageMode
import ai.roadscan.core.video.MontagePlan
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/**
 * Сборка готового MP4: титул → фрагменты с дефектами → сводка.
 *
 * Кадры берутся из исходника по таймкодам, на них рисуются рамки и титры,
 * затем всё кодируется в H.264. Никакой сети и сторонних сервисов.
 */
class VideoMontageRenderer(
    private val context: Context,
    private val info: VideoInfo,
    private val plan: MontagePlan,
    private val texts: VideoTexts,
    private val demoWatermark: Boolean,
) {

    private val outputWidth: Int
    private val outputHeight: Int
    private val fps: Int

    init {
        val maxSide = 1280f
        val scale = min(1f, maxSide / max(info.width, info.height).toFloat())
        outputWidth = even((info.width * scale).roundToInt().coerceAtLeast(2))
        outputHeight = even((info.height * scale).roundToInt().coerceAtLeast(2))
        fps = if (plan.mode == MontageMode.HIGHLIGHTS) 24 else 15
    }

    fun render(
        output: File,
        isActive: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
    ): File {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val brander = VideoBrander(outputWidth, outputHeight, texts, demoWatermark)
        val canvasBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        var decoder: VideoStreamDecoder? = null

        val introFrames = framesFor(VideoBrander.INTRO_MS)
        val outroFrames = if (plan.isEmpty) framesFor(VideoBrander.NO_DEFECTS_MS) else framesFor(VideoBrander.OUTRO_MS)
        val segmentFrames = plan.segments.sumOf { framesFor(it.durationMs) }
        val totalFrames = (introFrames + segmentFrames + outroFrames).coerceAtLeast(1)
        var done = 0
        var missingFrames = 0

        val dateMs = System.currentTimeMillis()
        val ids = eventIds()
        val encoder = Mp4Encoder(outputWidth, outputHeight, fps, output)

        try {
            // Внутри try: конструктор декодера бросает на неподдерживаемом
            // кодеке, и тогда файл не должен остаться на диске.
            decoder = VideoStreamDecoder(context, info)
            // Титул
            if (plan.isEmpty) {
                brander.drawNoDefects(canvas, dateMs)
            } else {
                brander.drawIntro(
                    canvas,
                    info,
                    plan,
                    plan.mode,
                    dateMs,
                )
            }
            val introCard = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            Canvas(introCard).drawBitmap(canvasBitmap, 0f, 0f, null)
            repeat(introFrames) {
                ensureActive(isActive)
                encoder.encode(introCard)
                done++
                onProgress(done.toFloat() / totalFrames)
            }
            introCard.recycle()

            val allBranded = plan.segments
                .flatMap { it.events }
                .sortedBy { it.peak.timeMs }
                .map { BrandedEvent(it, ids[it] ?: "D-000") }

            plan.segments.forEachIndexed { index, segment ->
                val branded = segment.events.map { BrandedEvent(it, ids[it] ?: "D-000") }
                val frames = framesFor(segment.durationMs)
                decoder?.seekTo(segment.startMs)
                for (frameIndex in 0 until frames) {
                    ensureActive(isActive)
                    val timeMs = segment.startMs + frameIndex * 1_000L / fps
                    val source = decoder?.advanceTo(timeMs)
                    if (source == null) {
                        // Конец файла или провал seek: держим последний кадр,
                        // иначе ролик «съедает» длительность и титры разъезжаются.
                        missingFrames++
                        encoder.encode(canvasBitmap)
                        done++
                        onProgress(done.toFloat() / totalFrames)
                        continue
                    }

                    val active = when (plan.mode) {
                        MontageMode.HIGHLIGHTS -> branded
                        MontageMode.FULL -> branded.filter {
                            timeMs in it.event.startMs..it.event.endMs
                        }
                    }
                    val foundSoFar = plan.segments
                        .take(index)
                        .sumOf { it.events.size } +
                        branded.count { it.event.peak.timeMs <= timeMs }
                    brander.drawVideoFrame(
                        canvas = canvas,
                        frame = source,
                        sourceTimeMs = timeMs,
                        sourceDurationMs = info.durationMs,
                        activeEvents = active,
                        segmentIndex = index + 1,
                        segmentCount = plan.segments.size.coerceAtLeast(1),
                        foundSoFar = if (plan.mode == MontageMode.HIGHLIGHTS) {
                            (index + 1).coerceAtMost(plan.eventCount)
                        } else {
                            foundSoFar
                        },
                        allEvents = allBranded,
                    )
                    encoder.encode(canvasBitmap)
                    done++
                    onProgress(done.toFloat() / totalFrames)
                }
            }

            // Финальная сводка или карточка «дефектов нет»
            if (plan.isEmpty) {
                brander.drawNoDefects(canvas, dateMs)
            } else {
                brander.drawSummary(canvas, plan, plan.mode, dateMs)
            }
            val outroCard = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            Canvas(outroCard).drawBitmap(canvasBitmap, 0f, 0f, null)
            repeat(outroFrames) {
                ensureActive(isActive)
                encoder.encode(outroCard)
                done++
                onProgress(done.toFloat() / totalFrames)
            }
            outroCard.recycle()

            if (missingFrames > 0) {
                android.util.Log.i(
                    "RoadScanVideo",
                    "кадров не хватило: $missingFrames из $totalFrames",
                )
            }
            encoder.finish()
            if (!encoder.isComplete || !output.exists() || output.length() < MIN_OUTPUT_BYTES) {
                throw IllegalStateException("ролик не записан: файл повреждён или пуст")
            }
        } catch (error: Throwable) {
            runCatching { encoder.close() }
            runCatching { output.delete() }
            throw error
        } finally {
            canvasBitmap.recycle()
            runCatching { encoder.close() }
            runCatching { decoder?.close() }
        }

        return output
    }

    private fun eventIds(): Map<MontageEvent, String> = eventIds(plan)

    companion object {
        /**
         * Нумерация находок ролика: «P-001», «C-002»… в порядке появления.
         * Общая для рендерера (рамки на кадрах) и списка находок в UI —
         * номера обязаны совпадать.
         */
        fun eventIds(plan: MontagePlan): Map<MontageEvent, String> {
            val counters = mutableMapOf<DefectType, Int>()
            val result = mutableMapOf<MontageEvent, String>()
            plan.segments
                .flatMap { it.events }
                .sortedBy { it.peak.timeMs }
                .forEach { event ->
                    val number = (counters[event.type] ?: 0) + 1
                    counters[event.type] = number
                    result[event] = "%s-%03d".format(VideoSeverity.prefix(event.type), number)
                }
            return result
        }

        /**
         * Таймкод находки в ГОТОВОМ ролике: титул + все предыдущие
         * фрагменты + смещение внутри фрагмента. По нему превью
         * перескакивает к моменту, где видно дефект.
         */
        fun outputTimeMs(plan: MontagePlan, event: MontageEvent): Long {
            var offset = VideoBrander.INTRO_MS
            for (segment in plan.segments) {
                if (segment.events.any { it === event }) {
                    val inside = (event.peak.timeMs - segment.startMs).coerceIn(0L, segment.durationMs)
                    return offset + inside
                }
                offset += segment.durationMs
            }
            return VideoBrander.INTRO_MS
        }

        /** Минимальный размер валидного ролика: меньше — файл заведомо битый. */
        private const val MIN_OUTPUT_BYTES = 1_024L

        fun outputFile(directory: File, prefix: String = "roadscan_review"): File {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            directory.mkdirs()
            return File(directory, "${prefix}_$stamp.mp4")
        }
    }

    private fun framesFor(durationMs: Long): Int =
        ((durationMs * fps + 999) / 1_000).toInt().coerceAtLeast(1)

    private fun ensureActive(isActive: () -> Boolean) {
        if (!isActive()) throw CancellationException("video montage cancelled")
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value - 1

    /** Для отображения в UI: какие типы и сколько раз попали в ролик. */
    fun summary(): List<Pair<DefectType, Int>> = plan.segments
        .flatMap { it.events }
        .groupingBy { it.type }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }

    fun outputSize(): Pair<Int, Int> = outputWidth to outputHeight
    fun outputFps(): Int = fps

}
