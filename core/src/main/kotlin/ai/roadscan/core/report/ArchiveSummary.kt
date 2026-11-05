package ai.roadscan.core.report

/** Одна сохранённая смена для сводки по архиву. */
data class ArchiveSession(
    val startedAtMs: Long,
    val confirmed: Int,
    val visualOnly: Int,
    val areaM2: Double,
    val cost: Double,
)

/**
 * Сводка по всем сохранённым сменам: общие итоги и динамика —
 * растёт или снижается число дефектов от смены к смене.
 */
data class ArchiveSummary(
    val sessions: Int,
    val defects: Int,
    val confirmed: Int,
    val areaM2: Double,
    val cost: Double,
    val avgDefects: Double,
    /** Изменение дефектов последней смены к предыдущей, %; null — смен меньше двух. */
    val trendPercent: Double?,
    val latestAtMs: Long,
) {
    companion object {
        val EMPTY = ArchiveSummary(
            sessions = 0,
            defects = 0,
            confirmed = 0,
            areaM2 = 0.0,
            cost = 0.0,
            avgDefects = 0.0,
            trendPercent = null,
            latestAtMs = 0L,
        )

        fun of(sessions: List<ArchiveSession>): ArchiveSummary {
            if (sessions.isEmpty()) return EMPTY
            val sorted = sessions.sortedBy { it.startedAtMs }
            val defects = sessions.sumOf { it.confirmed + it.visualOnly }
            val trend = if (sorted.size >= 2) {
                val prev = sorted[sorted.size - 2]
                val last = sorted.last()
                val prevCount = prev.confirmed + prev.visualOnly
                val lastCount = last.confirmed + last.visualOnly
                when {
                    prevCount > 0 -> (lastCount - prevCount) * 100.0 / prevCount
                    lastCount > 0 -> 100.0
                    else -> 0.0
                }
            } else {
                null
            }
            return ArchiveSummary(
                sessions = sessions.size,
                defects = defects,
                confirmed = sessions.sumOf { it.confirmed },
                areaM2 = sessions.sumOf { it.areaM2 },
                cost = sessions.sumOf { it.cost },
                avgDefects = defects.toDouble() / sessions.size,
                trendPercent = trend,
                latestAtMs = sorted.last().startedAtMs,
            )
        }
    }
}
