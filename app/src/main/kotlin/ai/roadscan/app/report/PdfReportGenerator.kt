package ai.roadscan.app.report

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.i18n.DocStrings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PDF-відомість на android.graphics.pdf.PdfDocument — без сторонніх бібліотек
 * і без мережі. A4 595×842 pt, автоматичне розбиття на сторінки.
 *
 * Мова та валюта беруться з [DocStrings]: для України — українська
 * термінологія і гривня, для РФ — російська і рубль.
 */
class PdfReportGenerator(private val strings: DocStrings = DocStrings.DEFAULT) {

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
        val s = strings
        val currency = s.currencySymbol(report.estimate.currency)

        writer.text("RoadScan AI · ${s.reportTitle}", titlePaint, 40f, 56f)
        writer.text(
            "${s.sessionLabel} ${report.sessionId} · ${format(report.startedAtMs)} — ${format(report.endedAtMs)}",
            subtitlePaint,
            40f,
            72f,
        )

        var y = 96f
        val meta = report.meta
        val metaLines = buildList {
            meta.objectName.takeIf { it.isNotBlank() }?.let { add("${s.objectLabel}: $it") }
            meta.roadName.takeIf { it.isNotBlank() }?.let { add("${s.roadLabel}: $it") }
            meta.customer.takeIf { it.isNotBlank() }?.let { add("${s.customerLabel}: $it") }
            meta.contractor.takeIf { it.isNotBlank() }?.let { add("${s.contractorLabel}: $it") }
            meta.contractNumber.takeIf { it.isNotBlank() }?.let { add("${s.contractLabel}: $it") }
            listOfNotNull(
                meta.operatorName.takeIf { it.isNotBlank() }?.let { "${s.operatorLabel}: $it" },
                meta.vehicle.takeIf { it.isNotBlank() }?.let { "${s.vehicleLabel}: $it" },
                meta.weather.takeIf { it.isNotBlank() }?.let { "${s.weatherLabel}: $it" },
            ).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" · ")) }
        }
        metaLines.forEach { line ->
            writer.text(clip(line, 110), bodyPaint, 40f, y)
            y += 11f
        }
        if (metaLines.isNotEmpty()) y += 4f

        y = summary(writer, report, y, currency)
        y = estimate(writer, report, y + 10f, currency)
        clusters(writer, report, y + 10f, currency)

        writer.demo = report.meta.demo
        writer.calibrationNote = report.calibration?.let {
            val pitch = if (s.locale == "uk") "нахил" else "наклон"
            "${s.calibrationLabel}: H=%.2f м, $pitch=%.1f°, f=%s".format(
                it.cameraHeightM,
                it.cameraPitchDeg,
                it.focalLengthPx?.let { f -> "%.0f px".format(f) } ?: s.calibrationEstimate,
            )
        }

        writer.finish()
        return writer.toByteArray()
    }

    private fun summary(writer: Writer, report: SessionReport, startY: Float, currency: String): Float {
        var y = startY
        writer.text(strings.summaryTitle, bodyBoldPaint, 40f, y)
        y += 8f
        val cards = listOf(
            strings.areaLabel to "%.1f м²".format(report.estimate.totalAreaM2),
            strings.millingLabel to "%.2f м³".format(report.estimate.totalMillingVolumeM3),
            strings.asphaltLabel to "%.2f т".format(report.estimate.totalAsphaltTons),
            strings.estimateLabel to "%.0f $currency".format(report.estimate.totalRub),
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
            "${strings.confirmedLabel}: ${report.stats.confirmed} · " +
                "${strings.visualOnlyLabel}: ${report.stats.visualOnly} · " +
                "${strings.clustersLabel}: ${report.clusters.size}",
            bodyPaint,
            40f,
            y,
        )
        return y + 10f
    }

    private fun estimate(writer: Writer, report: SessionReport, startY: Float, currency: String): Float {
        var y = startY
        val columns = floatArrayOf(40f, 88f, 360f, 400f, 470f, 530f)
        val headers = listOf(
            strings.codeColumn,
            strings.nameColumn,
            strings.unitColumn,
            strings.quantityColumn,
            "${strings.priceColumn}, $currency",
            "${strings.amountColumn}, $currency",
        )
        writer.text(strings.estimateTitle, bodyBoldPaint, 40f, y)
        y += 14f
        writer.tableHeader(y, headers, columns)
        y += 16f

        for (line in report.estimate.lines) {
            if (writer.needsNewPage(y, 20f)) {
                writer.newPage()
                y = 56f
                writer.tableHeader(y, headers, columns)
                y += 16f
            }
            val name = clip(line.name, 44)
            writer.row(y)
            writer.text(line.code, bodyPaint, columns[0] + 2f, y + 10f)
            writer.text(name, bodyPaint, columns[1] + 2f, y + 10f)
            writer.text(line.unit, bodyPaint, columns[2] + 2f, y + 10f)
            writer.text("%.2f".format(line.quantity), bodyPaint, columns[3] + 2f, y + 10f)
            writer.text("%.2f".format(line.unitPriceRub), bodyPaint, columns[4] + 2f, y + 10f)
            writer.text("%.2f".format(line.totalRub), bodyPaint, columns[5] + 2f, y + 10f)
            y += 16f
        }

        y += 4f
        val vatText = if (strings.locale == "uk") {
            "${strings.subtotalLabel}: %.2f $currency · ${strings.vatLabel} %.0f%%: %.2f $currency · " +
                "${strings.totalLabel}: %.2f $currency"
        } else {
            "${strings.subtotalLabel}: %.2f $currency · ${strings.vatLabel} %.0f%%: %.2f $currency · " +
                "${strings.totalLabel}: %.2f $currency"
        }
        writer.text(
            vatText.format(
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

    private fun clusters(writer: Writer, report: SessionReport, startY: Float, currency: String): Float {
        var y = startY
        val columns = floatArrayOf(40f, 90f, 200f, 260f, 320f, 380f, 470f, 520f)
        val headers = listOf(
            strings.idColumn,
            strings.typeColumn,
            strings.statusColumn,
            "${strings.areaLabel}, м²",
            strings.maxDepthColumn,
            strings.severityColumn,
            strings.latitudeColumn,
            strings.longitudeColumn,
        )
        writer.text(strings.registerTitle, bodyBoldPaint, 40f, y)
        y += 14f
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
            writer.text(strings.defectType(cluster.type), bodyPaint, columns[1] + 2f, y + 10f)
            writer.text(strings.confirmation(cluster.status), bodyPaint, columns[2] + 2f, y + 10f)
            writer.text("%.2f м²".format(cluster.areaM2), bodyPaint, columns[3] + 2f, y + 10f)
            writer.text("%.1f мм".format(cluster.maxDepthMm), bodyPaint, columns[4] + 2f, y + 10f)
            writer.text(cluster.maxSeverity.toString(), bodyPaint, columns[5] + 2f, y + 10f)
            writer.text("%.6f".format(cluster.center.lat), bodyPaint, columns[6] + 2f, y + 10f)
            writer.text("%.6f".format(cluster.center.lon), bodyPaint, columns[7] + 2f, y + 10f)
            y += 16f
        }

        if (report.visualOnlyClusters.isNotEmpty()) {
            y += 6f
            writer.text(strings.visualOnlyNote, subtitlePaint, 40f, y)
        }
        return y
    }

    private fun clip(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"

    private fun format(epochMs: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(epochMs))

    private inner class Writer(private val document: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var canvas = android.graphics.Canvas()
        private var pageNumber = 0

        /** Демо-режим: на кожній сторінці діагональний водяний знак. */
        var demo: Boolean = false

        /** Рядок аудиту калібрування у підвалі. */
        var calibrationNote: String? = null

        fun startPage() {
            newPage()
        }

        fun newPage() {
            page?.let { finishPage(it) }
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(info)
            canvas = page!!.canvas
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
            page.canvas.drawText("${strings.footer} $pageNumber", 40f, PAGE_HEIGHT - 24f, footer)
            calibrationNote?.let {
                page.canvas.drawText(it, 40f, PAGE_HEIGHT - 35f, footer)
            }
            if (demo) {
                val watermark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(38, 220, 60, 40)
                    textSize = 72f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                page.canvas.save()
                page.canvas.rotate(-30f, PAGE_WIDTH / 2f, PAGE_HEIGHT / 2f)
                page.canvas.drawText(
                    strings.demoWatermark,
                    PAGE_WIDTH / 2f - 92f,
                    PAGE_HEIGHT / 2f,
                    watermark,
                )
                page.canvas.restore()
            }
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
