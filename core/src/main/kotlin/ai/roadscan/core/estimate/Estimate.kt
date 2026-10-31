package ai.roadscan.core.estimate

import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.geo.DefectCluster
import ai.roadscan.core.model.DefectType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PriceItem(
    val code: String,
    val name: String,
    val unit: String,
    val priceRub: Double,
)

@Serializable
data class PriceCatalog(
    val region: String = "RU-DEFAULT",
    val currency: String = "RUB",
    val vatRate: Double = 0.20,
    val items: List<PriceItem> = emptyList(),
) {
    fun priceOf(code: String): Double =
        items.firstOrNull { it.code == code }?.priceRub ?: error("No price for code $code")

    fun item(code: String): PriceItem =
        items.firstOrNull { it.code == code } ?: error("No price for code $code")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val DEFAULT = PriceCatalog(
            items = listOf(
                PriceItem("MILLING", "Фрезерование асфальтобетонного покрытия", "м³", 850.0),
                PriceItem("ASPHALT", "Асфальтобетонная смесь, тип Б I", "т", 9_800.0),
                PriceItem("CRACK_SEAL", "Заделка трещин битумной мастикой", "м²", 450.0),
                PriceItem("DISPOSAL", "Вывоз и утилизация фрезерованного материала", "м³", 700.0),
                PriceItem("MOBILIZATION", "Мобилизация бригады и техники", "усл.", 15_000.0),
                PriceItem("TRAFFIC", "Ограждение места работ и организация движения", "усл.", 8_000.0),
                PriceItem("LABOR", "Дорожные рабочие (подготовка основания)", "чел·час", 950.0),
            ),
        )

        fun fromJson(text: String): PriceCatalog = json.decodeFromString(text)

        fun toJson(catalog: PriceCatalog): String = json.encodeToString(catalog)
    }
}

data class EstimateLine(
    val code: String,
    val name: String,
    val unit: String,
    val quantity: Double,
    val unitPriceRub: Double,
) {
    val totalRub: Double get() = quantity * unitPriceRub
}

data class Estimate(
    val currency: String,
    val lines: List<EstimateLine>,
    val subtotalRub: Double,
    val vatRate: Double,
    val vatRub: Double,
    val totalRub: Double,
    val totalAreaM2: Double,
    val totalMillingVolumeM3: Double,
    val totalAsphaltTons: Double,
) {
    companion object {
        val EMPTY = Estimate("RUB", emptyList(), 0.0, 0.2, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Расчёт сметы по подтверждённым кластерам.
 *
 * Объём фрезеровки: V = S · (H + allowance) / 1000, м³
 * Тоннаж асфальтобетона: M = V · ρ · K_уплотнения, т
 */
class EstimateCalculator(
    private val config: AgentConfig = AgentConfig.DEFAULT,
    private val catalog: PriceCatalog = PriceCatalog.DEFAULT,
    private val laborHoursPer100M2: Double = 4.0,
) {
    fun calculate(clusters: List<DefectCluster>): Estimate {
        val confirmed = clusters.filter { it.confirmed }
        if (confirmed.isEmpty()) return Estimate.EMPTY

        var area = 0.0
        var volume = 0.0
        var tons = 0.0
        var crackArea = 0.0

        for (cluster in confirmed) {
            area += cluster.areaM2
            when (cluster.type) {
                DefectType.CRACK_NETWORK -> crackArea += cluster.areaM2
                else -> {
                    val v = cluster.areaM2 *
                        (cluster.avgDepthMm.coerceAtLeast(1.0) + config.millingAllowanceMm) / 1000.0
                    volume += v
                    tons += v * config.asphaltDensityTPerM3 * config.compactionFactor
                }
            }
        }

        val lines = mutableListOf<EstimateLine>()
        if (volume > 0.0) {
            lines += line("MILLING", volume)
            lines += line("ASPHALT", tons)
            lines += line("DISPOSAL", volume)
            lines += line("LABOR", area / 100.0 * laborHoursPer100M2)
        }
        if (crackArea > 0.0) {
            lines += line("CRACK_SEAL", crackArea)
        }
        lines += line("MOBILIZATION", 1.0)
        lines += line("TRAFFIC", 1.0)

        val subtotal = lines.sumOf { it.totalRub }
        val vat = subtotal * catalog.vatRate
        return Estimate(
            currency = catalog.currency,
            lines = lines,
            subtotalRub = subtotal,
            vatRate = catalog.vatRate,
            vatRub = vat,
            totalRub = subtotal + vat,
            totalAreaM2 = area,
            totalMillingVolumeM3 = volume,
            totalAsphaltTons = tons,
        )
    }

    private fun line(code: String, quantity: Double): EstimateLine {
        val item = catalog.item(code)
        return EstimateLine(item.code, item.name, item.unit, quantity, item.priceRub)
    }
}
