package ai.roadscan.app.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.i18n.DocStrings
import ai.roadscan.core.report.GeoJsonExporter
import ai.roadscan.core.report.ReportXlsxBuilder
import java.io.File

enum class ReportFormat(val extension: String, val mime: String) {
    PDF("pdf", "application/pdf"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    GEOJSON("geojson", "application/geo+json"),
    /** Комплект документов: ведомость + смета + геоданные + манифест, одним ZIP. */
    PACKAGE("zip", "application/zip"),
}

/** Формирование пакета документов и шаринг через FileProvider. */
class ExportManager(
    private val context: Context,
    private val strings: DocStrings = DocStrings.DEFAULT,
) {

    fun export(report: SessionReport, format: ReportFormat): File {
        val dir = File(context.cacheDir, "exports/${report.sessionId}").apply { mkdirs() }
        val file = File(dir, "${report.sessionId}.${format.extension}")
        when (format) {
            ReportFormat.PDF -> file.writeBytes(PdfReportGenerator(strings).generate(report))
            ReportFormat.XLSX -> file.writeBytes(ReportXlsxBuilder.build(report, strings))
            ReportFormat.GEOJSON -> file.writeText(GeoJsonExporter.export(report, strings = strings))
            ReportFormat.PACKAGE -> file.writeBytes(buildPackage(report))
        }
        return file
    }

    /**
     * Комплект для передачи заказчику: ведомость, смета, геоданные и
     * манифест с реквизитами и контрольными суммами — одним архивом.
     */
    private fun buildPackage(report: SessionReport): ByteArray {
        val files = listOf(
            ai.roadscan.core.report.PackageFile(
                name = "VEDOMIST.pdf",
                content = PdfReportGenerator(strings).generate(report),
                role = "Ведомость дефектів",
            ),
            ai.roadscan.core.report.PackageFile(
                name = "KOSHTORYS.xlsx",
                content = ReportXlsxBuilder.build(report, strings),
                role = "Кошторис",
            ),
            ai.roadscan.core.report.PackageFile(
                name = "OBYEKT.geojson",
                content = GeoJsonExporter.export(report, strings = strings).toByteArray(Charsets.UTF_8),
                role = "Геодані для ГІС",
            ),
        )
        return ai.roadscan.core.report.PackageBuilder.build(
            sessionId = report.sessionId,
            meta = report.meta,
            files = files,
        )
    }

    fun shareIntent(file: File, format: ReportFormat): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData обязателен: без него системный лист выбора (отдельный
            // процесс) не получает URI-грант и получатель файла получает
            // Permission Denial вместо документа.
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
