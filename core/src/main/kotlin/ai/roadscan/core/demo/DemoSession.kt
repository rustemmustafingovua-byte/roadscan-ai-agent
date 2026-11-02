package ai.roadscan.core.demo

import ai.roadscan.core.agent.SessionData
import ai.roadscan.core.agent.SessionStats
import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectRecord
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.GeoPoint
import ai.roadscan.core.report.ReportMeta
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Генератор демонстрационной смены.
 *
 * Зачем это в коммерческом продукте:
 *  - отдел продаж и тендерный отдел показывают заказчику полный пакет
 *    документов (PDF/Excel/GeoJSON) без выезда и без реальной съёмки;
 *  - служба эксплуатации обучает операторов на «эталоне» — видно, как
 *    выглядят кластеры, критичность и смета;
 *  - QA и приёмка проверяют экспорт после релиза на любом устройстве.
 *
 * Данные синтетические и детерминированные (фиксированный seed), поэтому
 * одинаковый отчёт можно приложить к инструкции или сравнить «до/после».
 * В шапке документов такие смены всегда помечаются как демонстрационные.
 */
object DemoSession {

    /** Опорная точка демонстрационного участка под Киевом (синтетические координаты). */
    const val ORIGIN_LAT = 50.450100
    const val ORIGIN_LON = 30.523400

    /** Длина демонстрационного маршрута, м. */
    const val ROUTE_LENGTH_M = 4_800.0

    /** Длительность смены, мс (12 минут при средней скорости ~24 км/ч). */
    const val DURATION_MS = 12 * 60_000L

    fun build(
        startedAtMs: Long = System.currentTimeMillis() - DURATION_MS,
        seed: Long = 20_261_101L,
        sessionId: String = "ДЕМО-20КМ",
    ): SessionData {
        val rng = Lcg(seed)
        val defects = mutableListOf<DefectRecord>()
        var distance = 0.0
        var index = 0

        while (true) {
            distance += rng.range(140.0, 330.0)
            if (distance > ROUTE_LENGTH_M) break

            val type = pickType(rng)
            val clusterConfirmed = rng.next() < 0.72
            val members = rng.int(1, 3)

            repeat(members) {
                val along = distance + rng.range(-5.0, 5.0)
                val across = rng.range(-1.6, 1.6)
                val area = areaFor(type, rng)
                val confirmed = clusterConfirmed || rng.next() < 0.18
                val impact = if (confirmed) rng.range(0.30, 0.72) else 0.0
                val depth = if (confirmed) {
                    (10.0 + impact * 25.0 * rng.range(0.8, 1.6)).coerceAtMost(150.0)
                } else {
                    rng.range(4.0, 22.0)
                }
                val timestamp = startedAtMs + (along / ROUTE_LENGTH_M * DURATION_MS).toLong()
                val point = pointAt(along, across)

                defects += DefectRecord(
                    id = "DEF-DEMO%03d".format(index++),
                    sessionId = sessionId,
                    timestampMs = timestamp,
                    location = point,
                    type = type,
                    severity = severityOf(type, area, depth, confirmed),
                    confirmed = confirmed,
                    status = if (confirmed) {
                        ConfirmationStatus.CONFIRMED_PHYSICAL
                    } else {
                        ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY
                    },
                    estimatedAreaM2 = round2(area),
                    estimatedDepthMm = round2(depth),
                    visionConfidence = round2(rng.range(0.42, 0.93)).toFloat(),
                    impactG = round2(impact).toFloat(),
                    nearField = rng.next() < 0.82,
                )
            }
        }

        val stats = SessionStats(
            framesSeen = (DURATION_MS / 1000L * 20).toInt(),
            framesAnalyzed = (DURATION_MS / 1000L * 12).toInt(),
            framesSkippedByThrottle = (DURATION_MS / 1000L * 8).toInt(),
            rawDetections = defects.size + defects.size / 3,
            confirmed = defects.count { it.confirmed },
            visualOnly = defects.count { !it.confirmed },
            duplicatesMerged = defects.size / 4,
            lowConfidenceFiltered = defects.size / 2,
            noGeoRejected = 3,
            peakCpuTempC = 41.6f,
            averageLatencyMs = 38.4,
        )

        return SessionData(
            sessionId = sessionId,
            startedAtMs = startedAtMs,
            endedAtMs = startedAtMs + DURATION_MS,
            defects = defects,
            stats = stats,
        )
    }

    private fun pickType(rng: Lcg): DefectType = when {
        rng.next() < 0.34 -> DefectType.POTHOLE
        rng.next() < 0.58 -> DefectType.CRACK_NETWORK
        rng.next() < 0.78 -> DefectType.RUTTING
        rng.next() < 0.92 -> DefectType.PATCH
        else -> DefectType.MANHOLE_COVER
    }

    private fun areaFor(type: DefectType, rng: Lcg): Double = when (type) {
        DefectType.POTHOLE -> rng.range(0.35, 2.6)
        DefectType.CRACK_NETWORK -> rng.range(2.5, 11.0)
        DefectType.RUTTING -> rng.range(3.0, 9.0)
        DefectType.PATCH -> rng.range(1.2, 4.5)
        DefectType.MANHOLE_COVER -> rng.range(0.6, 1.4)
        DefectType.UNKNOWN -> 0.0
    }

    private fun severityOf(type: DefectType, area: Double, depthMm: Double, confirmed: Boolean): Int {
        if (!confirmed) return 1
        var score = 1
        if (depthMm >= 25) score++
        if (depthMm >= 45) score++
        if (area >= 1.5) score++
        if (type == DefectType.POTHOLE || type == DefectType.RUTTING) score++
        return score.coerceIn(1, 5)
    }

    /**
     * Реквизиты демонстрационного отчёта: украинская дорожная служба,
     * трасса М-06, договор подряда. Используются, если оператор ещё не
     * заполнил свой профиль.
     */
    fun defaultMeta(): ReportMeta = ReportMeta(
        objectName = "М-06 Київ — Чоп, км 45+200 — 49+900",
        roadName = "М-06, км 45+200 — 49+900",
        customer = "Служба відновлення та розвитку інфраструктури",
        contractor = "ТОВ «Дорбуд-Сервіс»",
        contractNumber = "№ 24/ДБ-118",
        operatorName = "І. Коваленко",
        vehicle = "Renault Master, AA 1234 ІК",
        weather = "Сухо, +18 °C",
    )

    /** Смещение от опорной точки по азимуту маршрута, метры. */
    private fun pointAt(alongM: Double, acrossM: Double): GeoPoint {
        val heading = Math.toRadians(92.0)
        val north = alongM * cos(heading) + acrossM * -sin(heading)
        val east = alongM * sin(heading) + acrossM * cos(heading)
        val lat = ORIGIN_LAT + north / 111_320.0
        val lon = ORIGIN_LON + east / (111_320.0 * cos(Math.toRadians(ORIGIN_LAT)))
        // Демоданные: координаты округлены до ~0.1 м, точность фиксированная.
        return GeoPoint(
            lat = round6(lat),
            lon = round6(lon),
            accuracyM = 4.5f,
        )
    }

    private fun sin(rad: Double) = Math.sin(rad)

    private fun round2(v: Double): Double = (v * 100).roundToInt() / 100.0

    private fun round6(v: Double): Double = (v * 1_000_000).roundToInt() / 1_000_000.0

    /** Линейный конгруэнтный генератор: воспроизводимая последовательность без зависимостей. */
    private class Lcg(seed: Long) {
        private var state = seed

        fun next(): Double {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return (state ushr 11).toDouble() / (1L shl 53).toDouble()
        }

        fun range(from: Double, to: Double): Double = from + (to - from) * next()

        fun int(from: Int, to: Int): Int =
            (from + next() * (to - from + 1)).toInt().coerceIn(from, to)
    }
}
