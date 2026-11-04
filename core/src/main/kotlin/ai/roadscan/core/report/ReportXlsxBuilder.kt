package ai.roadscan.core.report

import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.i18n.DocStrings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** Сборка Excel-кошторису та відомості з готового звіту. */
object ReportXlsxBuilder {

    private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    private val zone: ZoneId = ZoneId.systemDefault()

    fun build(report: SessionReport, s: DocStrings = DocStrings.DEFAULT): ByteArray {
        val sheets = listOf(
            estimateSheet(report, s),
            registerSheet(report, s),
            defectsSheet(report, s),
            metricsSheet(report, s),
        )
        return XlsxWriter().write(sheets)
    }

    private fun estimateSheet(report: SessionReport, s: DocStrings): XlsxWriter.Sheet {
        val rows = mutableListOf<List<Any?>>()
        val meta = report.meta
        val currency = s.currencySymbol(report.estimate.currency)
        rows.add(listOf("${s.estimateTitle} RoadScan AI", null, null, null, null, null))
        if (meta.demo) rows.add(listOf(s.demoNotice, null, null, null, null, null))
        meta.objectName.takeIf { it.isNotBlank() }?.let { rows.add(listOf(s.objectLabel, it)) }
        meta.roadName.takeIf { it.isNotBlank() }?.let { rows.add(listOf(s.roadLabel, it)) }
        meta.customer.takeIf { it.isNotBlank() }?.let { rows.add(listOf(s.customerLabel, it)) }
        meta.contractor.takeIf { it.isNotBlank() }?.let { rows.add(listOf(s.contractorLabel, it)) }
        meta.contractNumber.takeIf { it.isNotBlank() }?.let { rows.add(listOf(s.contractLabel, it)) }
        meta.operatorName.takeIf { it.isNotBlank() }?.let { rows.add(listOf(s.operatorLabel, it)) }
        meta.vehicle.takeIf { it.isNotBlank() }?.let { rows.add(listOf(s.vehicleLabel, it)) }
        meta.weather.takeIf { it.isNotBlank() }?.let { rows.add(listOf(s.weatherLabel, it)) }
        rows.add(listOf(s.sessionLabelShort, report.sessionId))
        rows.add(listOf(s.startLabel, format(report.startedAtMs)))
        rows.add(listOf(s.endLabel, format(report.endedAtMs)))
        rows.add(listOf(s.currencyLabel, report.estimate.currency))
        report.calibration?.let { c ->
            rows.add(
                listOf(
                    s.calibrationLabel,
                    "H=%.2f м, ${if (s.locale == "uk") "нахил" else "наклон"}=%.1f°, f=%s".format(
                        c.cameraHeightM,
                        c.cameraPitchDeg,
                        c.focalLengthPx?.let { "%.0f px".format(it) } ?: s.calibrationEstimate,
                    ),
                ),
            )
        }
        rows.add(emptyList())
        val headerRow = rows.size
        val nameHeader = if (s.locale == "uk") {
            "${s.nameColumn} робіт/матеріалів"
        } else {
            "${s.nameColumn} работ/материалов"
        }
        rows.add(
            listOf(
                s.codeColumn,
                nameHeader,
                s.unitColumn,
                s.quantityColumn,
                "${s.priceColumn}, $currency",
                "${s.amountColumn}, $currency",
            ),
        )

        report.estimate.lines.forEach { line ->
            rows.add(
                listOf(
                    line.code,
                    line.name,
                    line.unit,
                    round(line.quantity, 3),
                    round(line.unitPrice, 2),
                    round(line.total, 2),
                ),
            )
        }

        rows.add(emptyList())
        rows.add(listOf(null, null, null, null, s.subtotalLabel, round(report.estimate.subtotal, 2)))
        rows.add(
            listOf(
                null, null, null, null,
                "${s.vatLabel} ${(report.estimate.vatRate * 100).roundToLong()}%",
                round(report.estimate.vat, 2),
            ),
        )
        rows.add(listOf(null, null, null, null, s.totalLabel, round(report.estimate.total, 2)))
        rows.add(emptyList())
        rows.add(listOf("${s.areaLabel}, м²", round(report.estimate.totalAreaM2, 2)))
        rows.add(listOf("${s.millingLabel}, м³", round(report.estimate.totalMillingVolumeM3, 3)))
        rows.add(listOf("${s.asphaltLabel}, т", round(report.estimate.totalAsphaltTons, 3)))

        return XlsxWriter.Sheet(
            name = s.sheetEstimate,
            rows = rows,
            columnWidths = listOf(12, 52, 8, 12, 14, 16),
            boldRows = setOf(0, headerRow, rows.size - 4),
        )
    }

    private fun registerSheet(report: SessionReport, s: DocStrings): XlsxWriter.Sheet {
        val rows = mutableListOf<List<Any?>>()
        rows.add(listOf(s.registerTitle))
        report.meta.headline().takeIf { it.isNotBlank() }?.let { rows.add(listOf(it)) }
        if (report.meta.demo) rows.add(listOf(s.demoNotice))
        rows.add(emptyList())
        val hasKm = (report.clusters + report.visualOnlyClusters).any { it.km != null }
        rows.add(
            listOf(
                s.idColumn, s.typeColumn, s.statusColumn, "${s.areaLabel}, м²",
                "${s.maxDepthColumn}., мм", "${s.avgDepthColumn}., мм", s.severityColumn,
                s.countColumn, s.latitudeColumn, s.longitudeColumn,
            ) + if (hasKm) listOf(s.kmColumn) else emptyList(),
        )

        report.clusters.forEach { c ->
            rows.add(
                listOf(
                    c.id,
                    s.defectType(c.type),
                    s.confirmation(c.status),
                    round(c.areaM2, 2),
                    round(c.maxDepthMm, 1),
                    round(c.avgDepthMm, 1),
                    c.maxSeverity,
                    c.defectCount,
                    round(c.center.lat, 7),
                    round(c.center.lon, 7),
                ) + if (hasKm) listOf(c.km?.let { round(it, 3) }) else emptyList(),
            )
        }

        if (report.visualOnlyClusters.isNotEmpty()) {
            rows.add(emptyList())
            rows.add(listOf(s.visualOnlySection))
            report.visualOnlyClusters.forEach { c ->
                rows.add(
                    listOf(
                        c.id,
                        s.defectType(c.type),
                        s.confirmation(c.status),
                        round(c.areaM2, 2),
                        round(c.maxDepthMm, 1),
                        round(c.avgDepthMm, 1),
                        c.maxSeverity,
                        c.defectCount,
                        round(c.center.lat, 7),
                        round(c.center.lon, 7),
                    ) + if (hasKm) listOf(c.km?.let { round(it, 3) }) else emptyList(),
                )
            }
        }

        return XlsxWriter.Sheet(
            name = s.sheetRegister,
            rows = rows,
            headerRowCount = 1,
            columnWidths = listOf(26, 20, 20, 12, 16, 16, 12, 9, 13, 13),
        )
    }

    private fun defectsSheet(report: SessionReport, s: DocStrings): XlsxWriter.Sheet {
        val uk = s.locale == "uk"
        val rows = mutableListOf<List<Any?>>()
        rows.add(listOf(if (uk) "Точкові записи дефектів" else "Точечные записи дефектов"))
        rows.add(emptyList())
        rows.add(
            listOf(
                s.idColumn,
                if (uk) "Час" else "Время",
                s.typeColumn,
                s.severityColumn,
                if (uk) "Підтверджено" else "Подтверждён",
                "${s.areaLabel}, м²",
                if (uk) "Глибина, мм" else "Глубина, мм",
                if (uk) "Впевненість" else "Уверенность",
                if (uk) "Удар, g" else "Удар, g",
                if (uk) "Ближнє поле" else "Ближнее поле",
                s.latitudeColumn,
                s.longitudeColumn,
                s.kmColumn,
            ),
        )
        val yes = if (uk) "так" else "да"
        val no = if (uk) "ні" else "нет"

        report.defects.forEach { d ->
            rows.add(
                listOf(
                    d.id,
                    format(d.timestampMs),
                    s.defectType(d.type),
                    d.severity,
                    if (d.confirmed) yes else no,
                    round(d.estimatedAreaM2, 2),
                    round(d.estimatedDepthMm, 1),
                    round(d.visionConfidence.toDouble(), 2),
                    round(d.impactG.toDouble(), 2),
                    if (d.nearField) yes else no,
                    d.latitude?.let { round(it, 7) },
                    d.longitude?.let { round(it, 7) },
                    d.km?.let { round(it, 3) },
                ),
            )
        }

        return XlsxWriter.Sheet(
            name = s.sheetDefects,
            rows = rows,
            headerRowCount = 1,
            columnWidths = listOf(14, 20, 20, 11, 13, 12, 12, 12, 9, 13, 13, 13, 11),
        )
    }

    private fun metricsSheet(report: SessionReport, s: DocStrings): XlsxWriter.Sheet {
        val st = report.stats
        val uk = s.locale == "uk"
        fun t(ukText: String, ruText: String) = if (uk) ukText else ruText
        val rows = mutableListOf<List<Any?>>(
            listOf(s.metricsTitle),
            emptyList(),
            listOf(s.metricColumn, s.valueColumn),
            listOf(t("Тривалість, с", "Длительность, с"), round(report.durationMs / 1000.0, 1)),
            listOf(t("Кадрів оброблено", "Кадров обработано"), st.framesAnalyzed),
            listOf(t("Кадрів пропущено (тротлінг)", "Кадров пропущено (троттлинг)"), st.framesSkippedByThrottle),
            listOf(t("Сирих детекцій", "Сырых детекций"), st.rawDetections),
            listOf(t("Підтверджено фізикою", "Подтверждено физикой"), st.confirmed),
            listOf(s.visualOnlyLabel, st.visualOnly),
            listOf(t("Відкинуто (дублікати, без GPS)", "Отброшено (дубликаты, без GPS)"), st.rejectedAsFalsePositive),
            listOf(t("Дублікатів об'єднано", "Дубликатов слито"), st.duplicatesMerged),
            listOf(t("Середня затримка кадру, мс", "Средняя задержка кадра, мс"), round(st.averageLatencyMs, 2)),
            listOf(
                t("Пікова температура SoC, °C", "Пиковая температура SoC, °C"),
                if (st.peakCpuTempC.isNaN()) t("н/д", "н/д") else round(st.peakCpuTempC.toDouble(), 1),
            ),
            listOf(t("Кластерів підтверджено", "Кластеров подтверждено"), report.clusters.size),
            listOf(t("Кластерів лише візуально", "Кластеров visual-only"), report.visualOnlyClusters.size),
        )
        return XlsxWriter.Sheet(
            name = s.sheetMetrics,
            rows = rows,
            boldRows = setOf(0, 2),
            columnWidths = listOf(38, 22),
        )
    }

    private fun format(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(formatter)

    private fun round(value: Double, digits: Int): Double {
        val factor = Math.pow(10.0, digits.toDouble())
        return Math.round(value * factor) / factor
    }

    private fun round(value: Float, digits: Int): Double = round(value.toDouble(), digits)
}
