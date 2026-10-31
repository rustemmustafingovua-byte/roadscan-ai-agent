package ai.roadscan.core.report

import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** Сборка Excel-сметы и ведомости из готового отчёта. */
object ReportXlsxBuilder {

    private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    private val zone: ZoneId = ZoneId.systemDefault()

    fun build(report: SessionReport): ByteArray {
        val sheets = listOf(
            estimateSheet(report),
            registerSheet(report),
            defectsSheet(report),
            metricsSheet(report),
        )
        return XlsxWriter().write(sheets)
    }

    private fun estimateSheet(report: SessionReport): XlsxWriter.Sheet {
        val rows = mutableListOf<List<Any?>>()
        rows.add(listOf("Смета RoadScan AI", null, null, null, null, null))
        rows.add(listOf("Сессия", report.sessionId))
        rows.add(listOf("Начало", format(report.startedAtMs)))
        rows.add(listOf("Окончание", format(report.endedAtMs)))
        rows.add(listOf("Валюта", report.estimate.currency))
        rows.add(emptyList())
        val headerRow = rows.size
        rows.add(listOf("Код", "Наименование работ/материалов", "Ед.", "Кол-во", "Цена, ₽", "Сумма, ₽"))

        report.estimate.lines.forEach { line ->
            rows.add(
                listOf(
                    line.code,
                    line.name,
                    line.unit,
                    round(line.quantity, 3),
                    round(line.unitPriceRub, 2),
                    round(line.totalRub, 2),
                ),
            )
        }

        rows.add(emptyList())
        rows.add(listOf(null, null, null, null, "Итого без НДС", round(report.estimate.subtotalRub, 2)))
        rows.add(
            listOf(
                null, null, null, null,
                "НДС ${(report.estimate.vatRate * 100).roundToLong()}%",
                round(report.estimate.vatRub, 2),
            ),
        )
        rows.add(listOf(null, null, null, null, "ВСЕГО", round(report.estimate.totalRub, 2)))
        rows.add(emptyList())
        rows.add(listOf("Площадь дефектов, м²", round(report.estimate.totalAreaM2, 2)))
        rows.add(listOf("Объём фрезеровки, м³", round(report.estimate.totalMillingVolumeM3, 3)))
        rows.add(listOf("Тоннаж асфальтобетона, т", round(report.estimate.totalAsphaltTons, 3)))

        return XlsxWriter.Sheet(
            name = "Смета",
            rows = rows,
            columnWidths = listOf(12, 52, 8, 12, 14, 16),
            boldRows = setOf(0, headerRow, rows.size - 4),
        )
    }

    private fun registerSheet(report: SessionReport): XlsxWriter.Sheet {
        val rows = mutableListOf<List<Any?>>()
        rows.add(listOf("Ведомость дефектов (кластеры)"))
        rows.add(emptyList())
        rows.add(
            listOf(
                "ID", "Тип", "Статус", "Площадь, м²", "Глубина макс., мм",
                "Глубина сред., мм", "Критичность", "Кол-во", "Широта", "Долгота",
            ),
        )

        report.clusters.forEach { c ->
            rows.add(
                listOf(
                    c.id,
                    typeName(c.type),
                    c.status.name,
                    round(c.areaM2, 2),
                    round(c.maxDepthMm, 1),
                    round(c.avgDepthMm, 1),
                    c.maxSeverity,
                    c.defectCount,
                    round(c.center.lat, 7),
                    round(c.center.lon, 7),
                ),
            )
        }

        if (report.visualOnlyClusters.isNotEmpty()) {
            rows.add(emptyList())
            rows.add(listOf("Кандидаты без физического подтверждения (в смету не включены)"))
            report.visualOnlyClusters.forEach { c ->
                rows.add(
                    listOf(
                        c.id,
                        typeName(c.type),
                        ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY.name,
                        round(c.areaM2, 2),
                        round(c.maxDepthMm, 1),
                        round(c.avgDepthMm, 1),
                        c.maxSeverity,
                        c.defectCount,
                        round(c.center.lat, 7),
                        round(c.center.lon, 7),
                    ),
                )
            }
        }

        return XlsxWriter.Sheet(
            name = "Ведомость",
            rows = rows,
            headerRowCount = 1,
            columnWidths = listOf(26, 18, 26, 12, 16, 16, 12, 9, 13, 13),
        )
    }

    private fun defectsSheet(report: SessionReport): XlsxWriter.Sheet {
        val rows = mutableListOf<List<Any?>>()
        rows.add(listOf("Точечные записи дефектов"))
        rows.add(emptyList())
        rows.add(
            listOf(
                "ID", "Время", "Тип", "Критичность", "Подтверждён", "Площадь, м²",
                "Глубина, мм", "Уверенность", "Удар, g", "Ближнее поле", "Широта", "Долгота",
            ),
        )

        report.defects.forEach { d ->
            rows.add(
                listOf(
                    d.id,
                    format(d.timestampMs),
                    typeName(d.type),
                    d.severity,
                    if (d.confirmed) "да" else "нет",
                    round(d.estimatedAreaM2, 2),
                    round(d.estimatedDepthMm, 1),
                    round(d.visionConfidence.toDouble(), 2),
                    round(d.impactG.toDouble(), 2),
                    if (d.nearField) "да" else "нет",
                    d.latitude?.let { round(it, 7) },
                    d.longitude?.let { round(it, 7) },
                ),
            )
        }

        return XlsxWriter.Sheet(
            name = "Дефекты",
            rows = rows,
            headerRowCount = 1,
            columnWidths = listOf(14, 20, 18, 11, 13, 12, 12, 12, 9, 13, 13, 13),
        )
    }

    private fun metricsSheet(report: SessionReport): XlsxWriter.Sheet {
        val s = report.stats
        val rows = mutableListOf<List<Any?>>(
            listOf("Метрики сессии"),
            emptyList(),
            listOf("Показатель", "Значение"),
            listOf("Длительность, с", round(report.durationMs / 1000.0, 1)),
            listOf("Кадров обработано", s.framesAnalyzed),
            listOf("Кадров пропущено (троттлинг)", s.framesSkippedByThrottle),
            listOf("Сырых детекций", s.rawDetections),
            listOf("Подтверждено физикой", s.confirmed),
            listOf("Visual-only", s.visualOnly),
            listOf("Отфильтровано как ложные", s.rejectedAsFalsePositive),
            listOf("Дубликатов слито", s.duplicatesMerged),
            listOf("Средняя задержка кадра, мс", round(s.averageLatencyMs, 2)),
            listOf(
                "Пиковая температура SoC, °C",
                if (s.peakCpuTempC.isNaN()) "н/д" else round(s.peakCpuTempC.toDouble(), 1),
            ),
            listOf("Кластеров подтверждено", report.clusters.size),
            listOf("Кластеров visual-only", report.visualOnlyClusters.size),
        )
        return XlsxWriter.Sheet(
            name = "Метрики",
            rows = rows,
            boldRows = setOf(0, 2),
            columnWidths = listOf(38, 22),
        )
    }

    private fun typeName(type: DefectType): String = when (type) {
        DefectType.POTHOLE -> "Яма"
        DefectType.CRACK_NETWORK -> "Сеть трещин"
        DefectType.RUTTING -> "Колея"
        DefectType.PATCH -> "Заплатка"
        DefectType.MANHOLE_COVER -> "Люк"
        DefectType.UNKNOWN -> "Неизвестно"
    }

    private fun format(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(formatter)

    private fun round(value: Double, digits: Int): Double {
        val factor = Math.pow(10.0, digits.toDouble())
        return Math.round(value * factor) / factor
    }

    private fun round(value: Float, digits: Int): Double = round(value.toDouble(), digits)
}
