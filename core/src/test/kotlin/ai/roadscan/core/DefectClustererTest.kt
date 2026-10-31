package ai.roadscan.core

import ai.roadscan.core.geo.DefectClusterer
import ai.roadscan.core.geo.GeoMath
import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefectClustererTest {

    private val clusterer = DefectClusterer()

    @Test
    fun `five metres of cracks become one polygon`() {
        val defects = (0..5).map { i ->
            Fixtures.defect(
                location = GeoMath.offset(Fixtures.ORIGIN, northM = i * 1.0, eastM = 0.0),
                type = DefectType.CRACK_NETWORK,
                areaM2 = 0.3,
            )
        }
        val clusters = clusterer.cluster(defects)
        assertEquals(1, clusters.size)
        assertEquals(6, clusters.first().defectCount)
        assertTrue(clusters.first().areaM2 >= 0.3 * 6)
        assertTrue(clusters.first().polygon.size >= 3)
    }

    @Test
    fun `far defects form separate clusters`() {
        val near = Fixtures.defect(location = GeoMath.offset(Fixtures.ORIGIN, 0.0, 0.0))
        val far = Fixtures.defect(location = GeoMath.offset(Fixtures.ORIGIN, 100.0, 0.0))
        val clusters = clusterer.cluster(listOf(near, far))
        assertEquals(2, clusters.size)
    }

    @Test
    fun `different defect types are not merged`() {
        val a = Fixtures.defect(location = Fixtures.ORIGIN, type = DefectType.POTHOLE)
        val b = Fixtures.defect(location = GeoMath.offset(Fixtures.ORIGIN, 0.5, 0.0), type = DefectType.CRACK_NETWORK)
        assertEquals(2, clusterer.cluster(listOf(a, b)).size)
    }

    @Test
    fun `cluster with one confirmed member is confirmed`() {
        val visual = Fixtures.defect(location = GeoMath.offset(Fixtures.ORIGIN, 0.5, 0.0), confirmed = false)
        val confirmed = Fixtures.defect(location = Fixtures.ORIGIN, confirmed = true)
        val cluster = clusterer.cluster(listOf(visual, confirmed)).single()
        assertEquals(ConfirmationStatus.CONFIRMED_PHYSICAL, cluster.status)
    }

    @Test
    fun `defects without geo are skipped`() {
        val noGeo = Fixtures.defect().copy(location = null, latitude = null, longitude = null)
        assertTrue(clusterer.cluster(listOf(noGeo)).isEmpty())
    }

    @Test
    fun `clustering is fast enough for a long shift`() {
        val defects = (0 until 3_000).map { i ->
            Fixtures.defect(
                location = GeoMath.offset(Fixtures.ORIGIN, northM = (i % 100) * 1.0, eastM = (i / 100) * 3.0),
                areaM2 = 0.4,
            )
        }
        val started = System.nanoTime()
        val clusters = clusterer.cluster(defects)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        assertTrue(clusters.isNotEmpty())
        assertTrue(elapsedMs < 3_000, "clustering took $elapsedMs ms")
    }
}
