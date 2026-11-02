package ai.roadscan.app.report

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
        }
        return file
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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
