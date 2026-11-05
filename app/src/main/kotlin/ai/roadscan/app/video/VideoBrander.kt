package ai.roadscan.app.video

import ai.roadscan.core.model.DefectType
import ai.roadscan.core.video.MontageEvent
import ai.roadscan.core.video.MontagePlan
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Тексты титров: берутся из ресурсов приложения (укр./рус.). */
data class VideoTexts(
    val title: String,
    val brandSubtitle: String,
    val introKicker: String,
    val introTitle: String,
    val introSubtitle: String,
    val introModeHighlights: String,
    val introModeFull: String,
    val introSource: String,
    val introDate: String,
    val introDuration: String,
    val introDefects: String,
    val summaryTitle: String,
    val summaryNote: String,
    val captionTime: String,
    val captionConfidence: String,
    val captionSeverity: String,
    val fragment: String,
    val noDefects: String,
    val noDefectsHint: String,
    val demo: String,
    val offlineNote: String,
    val typeNames: Map<DefectType, String>,
)

data class BrandedEvent(val event: MontageEvent, val id: String)

object VideoSeverity {
    fun of(type: DefectType, confidence: Float): Int = when (type) {
        DefectType.POTHOLE -> when {
            confidence >= 0.8f -> 5
            confidence >= 0.6f -> 4
            else -> 3
        }
        DefectType.RUTTING -> 4
        DefectType.CRACK_NETWORK -> 3
        DefectType.PATCH -> 2
        DefectType.MANHOLE_COVER -> 2
        DefectType.UNKNOWN -> 1
    }

    fun prefix(type: DefectType): String = when (type) {
        DefectType.POTHOLE -> "P"
        DefectType.CRACK_NETWORK -> "C"
        DefectType.RUTTING -> "R"
        DefectType.PATCH -> "S"
        DefectType.MANHOLE_COVER -> "M"
        DefectType.UNKNOWN -> "X"
    }
}

/**
 * Отрисовка титров и рамок на кадре ролика. Всё рисуется обычным Canvas:
 * никаких GL-шейдеров и внешних библиотек, поэтому одинаково работает на
 * любом устройстве и полностью офлайн.
 */
class VideoBrander(
    private val width: Int,
    private val height: Int,
    private val texts: VideoTexts,
    private val demoWatermark: Boolean = false,
) {
    private val k = width / 1280f

    private val accent = Color.rgb(255, 122, 51)
    private val good = Color.rgb(124, 208, 176)
    private val warn = Color.rgb(255, 200, 87)
    private val bad = Color.rgb(255, 107, 107)
    private val muted = Color.rgb(154, 166, 178)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = paint(Color.WHITE, 30f, bold = true)
    private val smallPaint = paint(muted, 19f)
    private val timePaint = monospace(Color.WHITE, 26f, bold = true)
    private val labelPaint = paint(Color.WHITE, 22f, bold = true)
    private val typePaint = paint(Color.WHITE, 38f, bold = true)
    private val bodyPaint = paint(Color.rgb(224, 230, 236), 22f)
    private val cardTitlePaint = paint(Color.WHITE, 54f, bold = true)
    private val cardBodyPaint = paint(Color.rgb(200, 208, 216), 26f)
    private val bigPaint = paint(accent, 120f, bold = true)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(214, 10, 14, 18) }
    private val panelBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 90, 102, 114)
        style = Paint.Style.STROKE
        strokeWidth = 2f * k
    }

    fun drawVideoFrame(
        canvas: Canvas,
        frame: Bitmap,
        sourceTimeMs: Long,
        sourceDurationMs: Long,
        activeEvents: List<BrandedEvent>,
        segmentIndex: Int,
        segmentCount: Int,
        foundSoFar: Int,
        allEvents: List<BrandedEvent> = emptyList(),
    ) {
        canvas.drawBitmap(frame, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)

        drawTopBar(canvas, sourceTimeMs, sourceDurationMs, segmentIndex, segmentCount)
        activeEvents.forEach { drawDetection(canvas, frame, it) }

        val focus = activeEvents.maxByOrNull { it.event.confidence }
        if (focus != null) {
            // Карточка встаёт на противоположную от рамки сторону, иначе
            // перекрывает сам дефект и подпись «ID · уверенность».
            val box = focus.event.peak.box
            val centerX = (box.left + box.right) / 2f
            drawInfoCard(
                canvas = canvas,
                item = focus,
                timeMs = sourceTimeMs,
                foundSoFar = foundSoFar,
                alignRight = centerX < 0.5f,
            )
        }
        drawDefectTimeline(canvas, sourceTimeMs, sourceDurationMs, allEvents)
        if (demoWatermark) drawWatermark(canvas)
    }

    /**
     * Лента находок внизу кадра: маркер каждого дефекта на своём месте
     * ролика + головка воспроизведения. Даже когда рамка ещё не появилась,
     * видно, где и сколько повреждений нашла система.
     */
    private fun drawDefectTimeline(
        canvas: Canvas,
        timeMs: Long,
        durationMs: Long,
        allEvents: List<BrandedEvent>,
    ) {
        if (allEvents.isEmpty() || durationMs <= 0) return
        val margin = 40f * k
        val y = height - 58f * k
        val trackH = 10f * k

        canvas.drawRoundRect(
            RectF(margin, y, width - margin, y + trackH),
            trackH / 2,
            trackH / 2,
            Paint().apply { color = Color.argb(170, 20, 26, 32) },
        )

        for (item in allEvents) {
            val pos = (item.event.peak.timeMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val x = margin + pos * (width - margin * 2)
            val color = defectColor(item.event.type)
            val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            canvas.drawCircle(x, y + trackH / 2, 7f * k, marker)
            // Номер находки над маркером — как в ведомости.
            canvas.drawText(
                item.id,
                x - 16f * k,
                y - 8f * k,
                paint(color, 17f, bold = true),
            )
        }

        // Головка воспроизведения.
        val pos = (timeMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val x = margin + pos * (width - margin * 2)
        val playhead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            strokeWidth = 3f * k
        }
        canvas.drawLine(x, y - 6f * k, x, y + trackH + 6f * k, playhead)
    }

    fun drawIntro(
        canvas: Canvas,
        info: VideoInfo,
        plan: MontagePlan,
        mode: ai.roadscan.core.video.MontageMode,
        dateMs: Long,
    ) {
        drawBackdrop(canvas)
        val left = 80f * k
        // Блок прижат к верхней трети: титул не должен наезжать на подпись
        // «обработано офлайн» даже на широком кадре 16:9.
        var y = height * 0.18f

        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        canvas.drawCircle(left + 8f * k, y - 16f * k, 8f * k, dot)
        drawText(canvas, texts.introKicker, paint(accent, 22f, bold = true), left + 28f * k, y)
        y += 68f * k

        y = drawWrapped(canvas, texts.introTitle, cardTitlePaint, left, y, width - left * 2, 64f * k, 2)
        y += 8f * k
        y = drawWrapped(canvas, texts.introSubtitle, cardBodyPaint, left, y, width - left * 2, 34f * k, 2)
        y += 46f * k

        val modeText = if (mode == ai.roadscan.core.video.MontageMode.HIGHLIGHTS) {
            texts.introModeHighlights
        } else {
            texts.introModeFull
        }
        drawChip(canvas, modeText, left, y, accent)
        y += 62f * k

        val rows = listOf(
            texts.introSource.format(clip(info.displayName, 46)) to timePaint,
            texts.introDate.format(formatDate(dateMs)) to timePaint,
            texts.introDuration.format(info.durationLabel) to timePaint,
            texts.introDefects.format(plan.eventCount) to paint(good, 28f, bold = true),
        )
        rows.forEach { (text, paint) ->
            canvas.drawText(text, left, y, paint)
            y += 40f * k
        }

        drawFooter(canvas, texts.offlineNote)
        if (demoWatermark) drawWatermark(canvas)
    }

    fun drawSummary(
        canvas: Canvas,
        plan: MontagePlan,
        mode: ai.roadscan.core.video.MontageMode,
        dateMs: Long,
    ) {
        drawBackdrop(canvas)
        val left = 80f * k
        var y = 200f * k

        canvas.drawText(texts.summaryTitle, left, y, cardTitlePaint)
        y += 40f * k

        val counts = plan.segments
            .flatMap { it.events }
            .groupingBy { it.type }
            .eachCount()
            .entries
            .sortedByDescending { it.value }

        canvas.drawText("${plan.eventCount}", left, y + 110f * k, bigPaint)
        canvas.drawText(
            if (mode == ai.roadscan.core.video.MontageMode.HIGHLIGHTS) {
                texts.introModeHighlights
            } else {
                texts.introModeFull
            },
            left + 230f * k,
            y + 60f * k,
            cardBodyPaint,
        )
        y += 160f * k

        counts.forEach { (type, count) ->
            val color = defectColor(type)
            canvas.drawCircle(left + 10f * k, y - 10f * k, 9f * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            canvas.drawText(texts.typeNames[type] ?: type.name, left + 34f * k, y, bodyPaint)
            canvas.drawText(
                "× $count",
                left + 560f * k,
                y,
                paint(color, 28f, bold = true),
            )
            y += 52f * k
        }

        y += 20f * k
        drawWrapped(canvas, texts.summaryNote, smallPaint, left, y, width - left * 2, 28f * k, 3)
        drawFooter(canvas, formatDate(dateMs))
        if (demoWatermark) drawWatermark(canvas)
    }

    fun drawNoDefects(canvas: Canvas, dateMs: Long) {
        drawBackdrop(canvas)
        val left = 80f * k
        canvas.drawText("✓", left, 300f * k, paint(good, 110f, bold = true))
        var y = 400f * k
        y = drawWrapped(canvas, texts.noDefects, cardTitlePaint, left, y, width - left * 2, 66f * k, 2)
        y += 20f * k
        drawWrapped(canvas, texts.noDefectsHint, cardBodyPaint, left, y, width - left * 2, 34f * k, 3)
        drawFooter(canvas, formatDate(dateMs))
        if (demoWatermark) drawWatermark(canvas)
    }

    // --- элементы ---

    private fun drawTopBar(
        canvas: Canvas,
        timeMs: Long,
        durationMs: Long,
        segmentIndex: Int,
        segmentCount: Int,
    ) {
        val barHeight = 96f * k
        val bar = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, barHeight,
                Color.argb(230, 5, 7, 9),
                Color.argb(0, 5, 7, 9),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), barHeight, bar)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        canvas.drawCircle(40f * k, 38f * k, 7f * k, dot)
        canvas.drawText(texts.title, 58f * k, 47f * k, titlePaint)
        canvas.drawText(
            "· ${texts.brandSubtitle}",
            58f * k + titlePaint.measureText(texts.title) + 12f * k,
            47f * k,
            smallPaint,
        )
        canvas.drawText(
            "${formatTime(timeMs)} / ${formatTime(durationMs)}",
            width - 40f * k - timePaint.measureText("${formatTime(timeMs)} / ${formatTime(durationMs)}"),
            47f * k,
            timePaint,
        )
        canvas.drawText(
            texts.fragment.format(segmentIndex, segmentCount),
            width - 40f * k - smallPaint.measureText(texts.fragment.format(segmentIndex, segmentCount)),
            75f * k,
            smallPaint,
        )

        if (durationMs > 0) {
            val progress = (timeMs.toFloat() / durationMs).coerceIn(0f, 1f)
            canvas.drawRect(0f, barHeight - 3f * k, width * progress, barHeight, Paint().apply { color = accent })
        }
    }

    /**
     * Силуэт повреждения внутри рамки: залитая полупрозрачная форма +
     * светящийся контур. Смотрится как «подсвеченное пятно», а не квадрат.
     */
    private fun drawSilhouette(
        canvas: Canvas,
        frame: Bitmap,
        item: BrandedEvent,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int,
    ) {
        val bw = (right - left).toInt()
        val bh = (bottom - top).toInt()
        if (bw < 8 || bh < 8) return

        // Маску считаем на малой сетке — до 64 px по стороне.
        val grid = 48
        val gw = minOf(grid, bw)
        val gh = minOf(grid, bh)
        val crop = runCatching {
            Bitmap.createScaledBitmap(
                Bitmap.createBitmap(
                    frame,
                    (item.event.peak.box.left * frame.width).toInt().coerceIn(0, frame.width - 2),
                    (item.event.peak.box.top * frame.height).toInt().coerceIn(0, frame.height - 2),
                    (item.event.peak.box.width * frame.width).toInt().coerceIn(1, frame.width),
                    (item.event.peak.box.height * frame.height).toInt().coerceIn(1, frame.height),
                ),
                gw,
                gh,
                true,
            )
        }.getOrNull() ?: return
        val pixels = IntArray(gw * gh)
        crop.getPixels(pixels, 0, gw, 0, 0, gw, gh)
        if (crop !== frame) crop.recycle()
        val luma = IntArray(gw * gh) { i ->
            val px = pixels[i]
            ((px shr 16 and 0xFF) * 3 + (px shr 8 and 0xFF) * 6 + (px and 0xFF)) / 10
        }
        val mask = ai.roadscan.core.video.DefectSilhouette.mask(luma, gw, gh, item.event.type)

        // Рисуем маску как столбцы прозрачного цвета — форма читается сразу.
        val fill = Paint().apply {
            this.color = (color and 0x00FFFFFF) or (0x55 shl 24)
        }
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * k
            this.color = (color and 0x00FFFFFF) or (0xAA shl 24)
        }
        val cellW = (right - left) / gw
        val cellH = (bottom - top) / gh
        for (gy in 0 until gh) {
            var runStart = -1
            for (gx in 0..gw) {
                val on = gx < gw && mask[gy * gw + gx]
                if (on && runStart < 0) runStart = gx
                if (!on && runStart >= 0) {
                    val x0 = left + runStart * cellW
                    val x1 = left + gx * cellW
                    val y0 = top + gy * cellH
                    canvas.drawRect(x0, y0, x1, y0 + cellH, fill)
                    // Контур сверху/снизу прогона — лёгкая обводка формы.
                    if (gy == 0 || !mask[(gy - 1) * gw + runStart]) {
                        canvas.drawLine(x0, y0, x1, y0, glow)
                    }
                    if (gy == gh - 1 || !mask[(gy + 1) * gw + runStart]) {
                        canvas.drawLine(x0, y0 + cellH, x1, y0 + cellH, glow)
                    }
                    runStart = -1
                }
            }
        }
    }

    private fun drawDetection(canvas: Canvas, frame: Bitmap, item: BrandedEvent) {
        val box = item.event.peak.box
        val left = box.left * width
        val top = box.top * height
        val right = box.right * width
        val bottom = box.bottom * height
        val color = defectColor(item.event.type)

        drawSilhouette(canvas, frame, item, left, top, right, bottom, color)

        canvas.drawRect(left, top, right, bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * k
            this.color = color
        })

        val corner = 28f * k
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f * k
            this.color = color
        }
        canvas.drawLine(left, top, left + corner, top, stroke)
        canvas.drawLine(left, top, left, top + corner, stroke)
        canvas.drawLine(right, top, right - corner, top, stroke)
        canvas.drawLine(right, top, right, top + corner, stroke)
        canvas.drawLine(left, bottom, left + corner, bottom, stroke)
        canvas.drawLine(left, bottom, left, bottom - corner, stroke)
        canvas.drawLine(right, bottom, right - corner, bottom, stroke)
        canvas.drawLine(right, bottom, right, bottom - corner, stroke)

        val label = "${item.id} · ${(item.event.confidence * 100).toInt()}%"
        val textWidth = labelPaint.measureText(label)
        val pad = 10f * k
        val chipHeight = 34f * k
        var chipTop = top - chipHeight - 4f * k
        if (chipTop < 100f * k) chipTop = top + 4f * k
        canvas.drawRoundRect(
            RectF(left, chipTop, left + textWidth + pad * 2, chipTop + chipHeight),
            6f * k,
            6f * k,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color },
        )
        canvas.drawText(label, left + pad, chipTop + 25f * k, labelPaint)
    }

    private fun drawInfoCard(
        canvas: Canvas,
        item: BrandedEvent,
        timeMs: Long,
        foundSoFar: Int,
        alignRight: Boolean,
    ) {
        val cardWidth = minOf(width - 80f * k, 760f * k)
        val cardLeft = if (alignRight) width - cardWidth - 40f * k else 40f * k
        val cardHeight = 142f * k
        val cardTop = height - cardHeight - 52f * k
        canvas.drawRoundRect(
            RectF(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight),
            14f * k,
            14f * k,
            panelPaint,
        )
        canvas.drawRoundRect(
            RectF(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight),
            14f * k,
            14f * k,
            panelBorder,
        )
        canvas.drawRect(
            cardLeft,
            cardTop,
            cardLeft + 6f * k,
            cardTop + cardHeight,
            Paint().apply { color = defectColor(item.event.type) },
        )

        val textLeft = cardLeft + 26f * k
        val typeName = texts.typeNames[item.event.type] ?: item.event.type.name
        canvas.drawText(typeName, textLeft, cardTop + 46f * k, typePaint)
        canvas.drawText(
            texts.captionSeverity.format(VideoSeverity.of(item.event.type, item.event.confidence)) +
                "  ·  " + texts.captionConfidence.format((item.event.confidence * 100).toInt()),
            textLeft,
            cardTop + 82f * k,
            bodyPaint,
        )
        canvas.drawText(
            texts.captionTime.format(formatTime(timeMs)) +
                if (foundSoFar > 0) "  ·  ${texts.introDefects.format(foundSoFar)}" else "",
            textLeft,
            cardTop + 116f * k,
            smallPaint,
        )
    }

    private fun drawBackdrop(canvas: Canvas) {
        canvas.drawColor(Color.rgb(8, 11, 14))
        val glow = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.rgb(24, 32, 40),
                Color.rgb(8, 11, 14),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
        canvas.drawRect(0f, 0f, width.toFloat(), 8f * k, Paint().apply { color = accent })
    }

    private fun drawChip(canvas: Canvas, text: String, x: Float, y: Float, color: Int) {
        val paint = paint(color, 22f, bold = true)
        val textWidth = paint.measureText(text)
        canvas.drawRoundRect(
            RectF(x, y - 32f * k, x + textWidth + 34f * k, y + 12f * k),
            18f * k,
            18f * k,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(38, 255, 122, 51) },
        )
        canvas.drawText(text, x + 17f * k, y, paint)
    }

    private fun drawFooter(canvas: Canvas, text: String) {
        canvas.drawText(text, 80f * k, height - 54f * k, smallPaint)
    }

    private fun drawWatermark(canvas: Canvas) {
        val watermark = paint(Color.argb(46, 255, 90, 60), 110f, bold = true)
        canvas.save()
        canvas.rotate(-28f, width / 2f, height / 2f)
        canvas.drawText(texts.demo, width / 2f - 120f * k, height / 2f, watermark)
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, text: String, paint: Paint, x: Float, y: Float) {
        canvas.drawText(text, x, y, paint)
    }

    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        paint: Paint,
        x: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float,
        maxLines: Int,
    ): Float {
        val words = text.split(' ')
        var line = StringBuilder()
        var y = startY
        var lines = 0
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line = StringBuilder(candidate)
            } else {
                if (line.isNotEmpty()) {
                    canvas.drawText(line.toString(), x, y, paint)
                    y += lineHeight
                    lines++
                }
                if (lines >= maxLines) return y
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty() && lines < maxLines) {
            canvas.drawText(line.toString(), x, y, paint)
            y += lineHeight
        }
        return y
    }

    private fun paint(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size * k
        typeface = Typeface.create(
            Typeface.SANS_SERIF,
            if (bold) Typeface.BOLD else Typeface.NORMAL,
        )
    }

    private fun monospace(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size * k
        typeface = Typeface.create(
            Typeface.MONOSPACE,
            if (bold) Typeface.BOLD else Typeface.NORMAL,
        )
    }

    private fun defectColor(type: DefectType): Int = when (type) {
        DefectType.POTHOLE -> Color.rgb(255, 107, 74)
        DefectType.CRACK_NETWORK -> warn
        DefectType.RUTTING -> Color.rgb(90, 169, 255)
        DefectType.PATCH -> good
        DefectType.MANHOLE_COVER -> Color.rgb(192, 139, 255)
        DefectType.UNKNOWN -> muted
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val minutes = total / 60
        val seconds = total % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun formatDate(ms: Long): String =
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ms))

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    companion object {
        const val INTRO_MS = 2_600L
        const val OUTRO_MS = 3_400L
        const val NO_DEFECTS_MS = 4_000L
    }
}
