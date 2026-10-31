package ai.roadscan.core

import ai.roadscan.core.estimate.EstimateCalculator
import ai.roadscan.core.estimate.PriceCatalog
import ai.roadscan.core.geo.DefectCluster
import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EstimateCalculatorTest {

    private val calculator = EstimateCalculator()

    private fun cluster(
        area: Double = 2.0,
        depth: Double = 40.0,
        confirmed: Boolean = true,
        type: DefectType = DefectType.POTHOLE,
    ) = DefectCluster(
        id = "CL-1",
        type = type,
        center = Fixtures.ORIGIN,
        polygon = emptyList(),
        areaM2 = area,
        maxDepthMm = depth,
        avgDepthMm = depth,
        maxSeverity = 4,
        defectCount = 1,
        status = if (confirmed) ConfirmationStatus.CONFIRMED_PHYSICAL else ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY,
    )

    @Test
    fun `milling volume and tonnage follow the formula`() {
        val estimate = calculator.calculate(listOf(cluster(area = 2.0, depth = 40.0)))

        // V = 2.0 * (40 + 30) / 1000 = 0.14 м³
        assertEquals(0.14, estimate.totalMillingVolumeM3, 1e-9)
        // M = 0.14 * 2.4 * 1.25 = 0.42 т
        assertEquals(0.42, estimate.totalAsphaltTons, 1e-9)

        val milling = estimate.lines.first { it.code == "MILLING" }
        val asphalt = estimate.lines.first { it.code == "ASPHALT" }
        assertEquals(0.14, milling.quantity, 1e-9)
        assertEquals(0.42, asphalt.quantity, 1e-9)
    }

    @Test
    fun `visual only clusters are not billed`() {
        val estimate = calculator.calculate(listOf(cluster(confirmed = false)))
        assertEquals(0.0, estimate.totalMillingVolumeM3)
        assertTrue(estimate.lines.isEmpty())
    }

    @Test
    fun `crack network is billed as sealing not milling`() {
        val estimate = calculator.calculate(listOf(cluster(area = 3.0, depth = 0.0, type = DefectType.CRACK_NETWORK)))
        assertEquals(0.0, estimate.totalMillingVolumeM3)
        assertEquals(3.0, estimate.lines.first { it.code == "CRACK_SEAL" }.quantity, 1e-9)
    }

    @Test
    fun `vat and total are consistent`() {
        val estimate = calculator.calculate(listOf(cluster()))
        val subtotal = estimate.lines.sumOf { it.totalRub }
        assertEquals(subtotal, estimate.subtotalRub, 1e-6)
        assertEquals(subtotal * 0.2, estimate.vatRub, 1e-6)
        assertEquals(subtotal * 1.2, estimate.totalRub, 1e-6)
    }

    @Test
    fun `mobilization and traffic management are always present`() {
        val estimate = calculator.calculate(listOf(cluster()))
        assertTrue(estimate.lines.any { it.code == "MOBILIZATION" })
        assertTrue(estimate.lines.any { it.code == "TRAFFIC" })
    }

    @Test
    fun `price catalog is overridable`() {
        val catalog = PriceCatalog.DEFAULT.copy(
            items = PriceCatalog.DEFAULT.items.map {
                if (it.code == "ASPHALT") it.copy(priceRub = 12_000.0) else it
            },
        )
        val estimate = EstimateCalculator(catalog = catalog).calculate(listOf(cluster()))
        assertEquals(12_000.0, estimate.lines.first { it.code == "ASPHALT" }.unitPriceRub, 1e-9)
    }

    @Test
    fun `estimate for ten thousand defects is under three seconds`() {
        val clusters = (0 until 10_000).map { cluster(area = 0.5, depth = 30.0) }
        val started = System.nanoTime()
        val estimate = calculator.calculate(clusters)
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        assertTrue(estimate.lines.isNotEmpty())
        assertTrue(elapsed < 3_000, "estimate took $elapsed ms")
    }
}
