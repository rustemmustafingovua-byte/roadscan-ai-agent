package ai.roadscan.core.agent

import ai.roadscan.core.estimate.EstimateCalculator
import ai.roadscan.core.estimate.PriceCatalog
import ai.roadscan.core.geo.DefectClusterer

/**
 * Аналитический контур: кластеризация + смета + подготовка отчёта.
 * Работает офлайн, без сети; укладывается в бюджет < 3 с на 10k дефектов.
 */
class PostProcessingAgent(
    private val config: AgentConfig = AgentConfig.DEFAULT,
    private val catalog: PriceCatalog = PriceCatalog.DEFAULT,
) {
    private val clusterer = DefectClusterer(config)
    private val calculator = EstimateCalculator(config, catalog)

    fun process(data: SessionData): SessionReport =
        process(data.sessionId, data.startedAtMs, data.endedAtMs, data.defects, data.stats)

    fun process(
        sessionId: String,
        startedAtMs: Long,
        endedAtMs: Long,
        defects: List<ai.roadscan.core.model.DefectRecord>,
        stats: SessionStats,
    ): SessionReport {
        val clusters = clusterer.cluster(defects)
        val confirmed = clusters.filter { it.confirmed }
        val visualOnly = clusters.filter { !it.confirmed }
        val estimate = calculator.calculate(confirmed)

        return SessionReport(
            sessionId = sessionId,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            defects = defects.sortedBy { it.timestampMs },
            clusters = confirmed,
            visualOnlyClusters = visualOnly,
            estimate = estimate,
            stats = stats,
        )
    }
}
