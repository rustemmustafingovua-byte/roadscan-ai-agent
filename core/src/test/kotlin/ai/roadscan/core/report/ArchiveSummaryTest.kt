package ai.roadscan.core.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveSummaryTest {

    private fun session(
        atMs: Long,
        confirmed: Int = 0,
        visualOnly: Int = 0,
        areaM2: Double = 0.0,
        cost: Double = 0.0,
    ) = ArchiveSession(atMs, confirmed, visualOnly, areaM2, cost)

    @Test
    fun `пустой архив — пустая сводка`() {
        val summary = ArchiveSummary.of(emptyList())
        assertEquals(0, summary.sessions)
        assertEquals(0, summary.defects)
        assertNull(summary.trendPercent)
    }

    @Test
    fun `итоги суммируются по всем сменам`() {
        val summary = ArchiveSummary.of(
            listOf(
                session(1000, confirmed = 3, visualOnly = 1, areaM2 = 12.5, cost = 100.0),
                session(2000, confirmed = 2, visualOnly = 0, areaM2 = 5.0, cost = 40.0),
            ),
        )
        assertEquals(2, summary.sessions)
        assertEquals(6, summary.defects)
        assertEquals(5, summary.confirmed)
        assertEquals(17.5, summary.areaM2)
        assertEquals(140.0, summary.cost)
        assertEquals(3.0, summary.avgDefects)
        assertEquals(2000L, summary.latestAtMs)
    }

    @Test
    fun `динамика — рост и спад к предыдущей смене`() {
        val up = ArchiveSummary.of(listOf(session(1, confirmed = 4), session(2, confirmed = 6)))
        assertEquals(50.0, up.trendPercent!!)

        val down = ArchiveSummary.of(listOf(session(1, confirmed = 8), session(2, confirmed = 4)))
        assertEquals(-50.0, down.trendPercent!!)
    }

    @Test
    fun `одна смена — динамики нет, нулевая к нулевой не растёт`() {
        assertNull(ArchiveSummary.of(listOf(session(1, confirmed = 5))).trendPercent)
        val flat = ArchiveSummary.of(listOf(session(1, confirmed = 0), session(2, confirmed = 0)))
        assertEquals(0.0, flat.trendPercent)
    }

    @Test
    fun `порядок смен по времени, а не по списку`() {
        val summary = ArchiveSummary.of(
            listOf(session(5000, confirmed = 1), session(1000, confirmed = 9)),
        )
        assertEquals(5000L, summary.latestAtMs)
        assertEquals(-88.9, summary.trendPercent!!, 0.1)
        assertTrue(summary.defects == 10)
    }
}
