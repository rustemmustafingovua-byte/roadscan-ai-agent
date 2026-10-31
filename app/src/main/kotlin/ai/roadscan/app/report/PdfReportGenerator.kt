package ai.roadscan.app.report

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.model.DefectType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * PDF-ведомость на android.graphics.pdf.PdfDocument — без сторонних библиотек
 * и без сети. A4 595×842 pt, автоматическая разбивка на страницы.
 */
class PdfReportGenerator {

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 24, 30)
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 100, 110)
        textSize = 9f
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 34, 40)
        textSize = 9f
    }
    private val bodyBoldPaint = Paint(bodyPaint).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val headerPaint = Paint(bodyBoldPaint).apply {
        color = Color.rgb(255, 255, 255)
        textSize = 8.5f
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(240, 110, 40)
        textSize = 9f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val linePaint = Paint().apply {
        color = Color.rgb(225, 228, 232)
        strokeWidth = 0.6f
    }

    fun generate(report: SessionReport): ByteArray {
        val document = PdfDocument()
        val writer = Writer(document)
        writer.startPage()

        writer.text("RoadScan AI · Ведомость дефектов", titlePaint, 40f, 56f)
        writer.text(
            "Сессия ${report.sessionId} · ${format(report.startedAtMs)} — ${format(report.endedAtMs)}",
            subtitlePaint,
            40f,
            72f,
        )

        var y = 96f
        y = summary(writer, report, y)
        y = estimate(writer, report, y + 10f)
        clusters(writer, report, y + 10f)

        writer.finish()
        return writer.toByteArray()
    }

    private fun summary(writer: Writer, report: SessionReport, startY: Float): Float {
        var y = startY
        writer.text("Сводка", bodyBoldPaint, 40f, y)
        y += 8f
        val cards = listOf(
            "Площадь" to "%.1f м²".format(Locale.US, report.estimate.totalAreaM2),
            "Фрезеровка" to "%.2f м³".format(Locale.US, report.estimate.totalMillingVolumeM3),
            "Асфальтобетон" to "%.2f т".format(Locale.US, report.estimate.totalAsphaltTons),
            "Смета" to "%.0f ₽".format(Locale.US, report.estimate.totalRub),
        )
        val cardWidth = 125f
        cards.forEachIndexed { index, (label, value) ->
            val x = 40f + index * (cardWidth + 10f)
            writer.box(x, y, cardWidth, 44f)
            writer.text(label, subtitlePaint, x + 10f, y + 16f)
            writer.text(value, bodyBoldPaint, x + 10f, y + 34f)
        }
        y += 58f
        writer.text(
            "Подтверждено: ${report.stats.confirmed} · Visual-only: ${report.stats.visualOnly} · " +
                "Кластеров: ${report.clusters.size}",
            bodyPaint,
            40f,
            y,
        )
        return y + 10f
    }

    private fun estimate(writer: Writer, report: SessionReport, startY: Float): Float {
        var y = startY
        writer.text("Смета", bodyBoldPaint, 40f, y)
        y += 14f
        val columns = floatArrayOf(40f, 88f, 360f, 400f, 470f, 530f)
        writer.tableHeader(
            y,
            listOf("Код", "Наименование", "Ед.", "Кол-во", "Цена, ₽", "Сумма, ₽"),
            columns,
        )
        y += 16f

        for (line in report.estimate.lines) {
            if (writer.needsNewPage(y, 20f)) {
                writer.newPage()
                y = 56f
                writer.tableHeader(
                    y,
                    listOf("Код", "Наименование", "Ед.", "Кол-во", "Цена, ₽", "Сумма, ₽"),
                    columns,
                )
                y += 16f
            }
            val name = clip(line.name, 44)
            writer.row(y)
            writer.text(line.code, bodyPaint, columns[0] + 2f, y + 10f)
            writer.text(name, bodyPaint, columns[1] + 2f, y + 10f)
            writer.text(line.unit, bodyPaint, columns[2] + 2f, y + 10f)
            writer.text("%.2f".format(Locale.US, line.quantity), bodyPaint, columns[3] + 2f, y + 10f)
            writer.text("%.2f".format(Locale.US, line.unitPriceRub), bodyPaint, columns[4] + 2f, y + 10f)
            writer.text("%.2f".format(Locale.US, line.totalRub), bodyPaint, columns[5] + 2f, y + 10f)
            y += 16f
        }

        y += 4f
        writer.text(
            "Итого без НДС: %.2f ₽ · НДС %.0f%%: %.2f ₽ · ВСЕГО: %.2f ₽".format(
                Locale.US,
                report.estimate.subtotalRub,
                report.estimate.vatRate * 100,
                report.estimate.vatRub,
                report.estimate.totalRub,
            ),
            accentPaint,
            40f,
            y + 8f,
        )
        return y + 18f
    }

    private fun clusters(writer: Writer, report: SessionReport, startY: Float): Float {
        var y = startY
        writer.text("Ведомость кластеров", bodyBoldPaint, 40f, y)
        y += 14f
        val columns = floatArrayOf(40f, 90f, 200f, 260f, 320f, 380f, 470f, 520f)
        val headers = listOf("ID", "Тип", "Статус", "Площадь", "Глуб. макс", "Критичность", "Широта", "Долгота")
        writer.tableHeader(y, headers, columns)
        y += 16f

        val rows = report.clusters + report.visualOnlyClusters
        for (cluster in rows) {
            if (writer.needsNewPage(y, 18f)) {
                writer.newPage()
                y = 56f
                writer.tableHeader(y, headers, columns)
                y += 16f
            }
            writer.row(y)
            writer.text(cluster.id, bodyPaint, columns[0] + 2f, y + 10f)
            writer.text(typeName(cluster.type), bodyPaint, columns[1] + 2f, y + 10f)
            writer.text(if (cluster.confirmed) "confirmed" else "visual-only", bodyPaint, columns[2] + 2f, y + 10f)
            writer.text("%.2f м²".format(Locale.US, cluster.areaM2), bodyPaint, columns[3] + 2f, y + 10f)
            writer.text("%.1f мм".format(Locale.US, cluster.maxDepthMm), bodyPaint, columns[4] + 2f, y + 10f)
            writer.text(cluster.maxSeverity.toString(), bodyPaint, columns[5] + 2f, y + 10f)
            writer.text("%.6f".format(Locale.US, cluster.center.lat), bodyPaint, columns[6] + 2f, y + 10f)
            writer.text("%.6f".format(Locale.US, cluster.center.lon), bodyPaint, columns[7] + 2f, y + 10f)
            y += 16f
        }

        if (report.visualOnlyClusters.isNotEmpty()) {
            y += 6f
            writer.text(
                "Visual-only кластеры в смету не включены (нет удара подвески).",
                subtitlePaint,
                40f,
                y,
            )
        }
        return y
    }

    private fun typeName(type: DefectType): String = when (type) {
        DefectType.POTHOLE -> "Яма"
        DefectType.CRACK_NETWORK -> "Сеть трещин"
        DefectType.RUTTING -> "Колея"
        DefectType.PATCH -> "Заплатка"
        DefectType.MANHOLE_COVER -> "Люк"
        DefectType.UNKNOWN -> "Неизвестно"
    }

    private fun clip(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"

    private fun format(epochMs: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(epochMs))

    private inner class Writer(private val document: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var canvas = android.graphics.Canvas()
        var y = 56f
            private set
        private var pageNumber = 0

        fun startPage() {
            newPage()
        }

        fun newPage() {
            page?.let { finishPage(it) }
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(info)
            canvas = page!!.canvas
            y = 56f
            canvas.drawColor(Color.WHITE)
        }

        fun text(text: String, paint: Paint, x: Float, y: Float) {
            canvas.drawText(text, x, y, paint)
        }

        fun box(x: Float, y: Float, width: Float, height: Float) {
            val paint = Paint().apply { color = Color.rgb(245, 246, 248) }
            canvas.drawRoundRect(x, y, x + width, y + height, 6f, 6f, paint)
        }

        fun row(y: Float) {
            canvas.drawLine(40f, y, PAGE_WIDTH - 40f, y, linePaint)
        }

        fun tableHeader(y: Float, headers: List<String>, columns: FloatArray) {
            val paint = Paint().apply { color = Color.rgb(28, 32, 38) }
            canvas.drawRect(40f, y - 2f, PAGE_WIDTH - 40f, y + 16f, paint)
            headers.forEachIndexed { index, header ->
                if (index < columns.size) canvas.drawText(header, columns[index] + 2f, y + 10f, headerPaint)
            }
        }

        fun needsNewPage(currentY: Float, needed: Float): Boolean =
            currentY + needed > PAGE_HEIGHT - 50f

        fun finish() {
            page?.let { finishPage(it) }
            page = null
        }

        private fun finishPage(page: PdfDocument.Page) {
            val footer = Paint(bodyPaint).apply { color = Color.rgb(140, 146, 154); textSize = 8f }
            page.canvas.drawText("RoadScan AI Agent · стр. $pageNumber", 40f, PAGE_HEIGHT - 24f, footer)
            document.finishPage(page)
        }

        fun toByteArray(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            document.writeTo(out)
            document.close()
            return out.toByteArray()
        }
    }

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
    }
}
