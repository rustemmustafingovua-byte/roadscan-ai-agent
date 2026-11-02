package ai.roadscan.core

import ai.roadscan.core.agent.PostProcessingAgent
import ai.roadscan.core.demo.DemoSession
import ai.roadscan.core.report.GeoJsonExporter
import ai.roadscan.core.report.ReportMeta
import ai.roadscan.core.report.ReportXlsxBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoSessionTest {

    private val started = 1_700_000_000_000L

    @Test
    fun `demo session is deterministic`() {
        val first = DemoSession.build(startedAtMs = started)
        val second = DemoSession.build(startedAtMs = started)
        assertEquals(first.defects.size, second.defects.size)
        assertEquals(first.defects.first().id, second.defects.first().id)
        assertEquals(first.defects.last().location, second.defects.last().location)
    }

    @Test
    fun `demo session is realistic and passes through the agent pipeline`() {
        val data = DemoSession.build(startedAtMs = started)
        assertTrue(data.defects.size in 20..80, "дефектов: ${data.defects.size}")
        assertTrue(data.defects.count { it.confirmed } > 5)
        assertTrue(data.defects.count { !it.confirmed } > 0)
        assertEquals(started, data.startedAtMs)
        assertTrue(data.endedAtMs > data.startedAtMs)

        val report = PostProcessingAgent().process(
            data.sessionId,
            data.startedAtMs,
            data.endedAtMs,
            data.defects,
            data.stats,
            meta = ReportMeta(objectName = "Демонстрационный участок"),
        )

        assertTrue(report.clusters.isNotEmpty(), "нет подтверждённых кластеров")
        assertTrue(report.estimate.totalRub > 0.0)
        assertTrue(report.estimate.totalAreaM2 > 0.0)
        assertTrue(report.estimate.totalMillingVolumeM3 > 0.0)
        // Кластеры не должны схлопываться в один: маршрут 4.8 км с разрывами.
        assertTrue(report.clusters.size >= 5, "кластеров: ${report.clusters.size}")
    }

    @Test
    fun `demo documents are exportable without a detector`() {
        val data = DemoSession.build(startedAtMs = started)
        val report = PostProcessingAgent().process(
            data.sessionId,
            data.startedAtMs,
            data.endedAtMs,
            data.defects,
            data.stats,
        )

        val xlsx = ReportXlsxBuilder.build(report)
        assertTrue(xlsx.size > 2_000)
        val geo = GeoJsonExporter.export(report)
        assertTrue(geo.contains("FeatureCollection"))
        assertTrue(geo.length > 500)
    }

    @Test
    fun `every demo defect has coordinates`() {
        val data = DemoSession.build(startedAtMs = started)
        assertTrue(data.defects.all { it.location != null })
        assertTrue(data.defects.all { it.estimatedAreaM2 > 0.0 })
        assertTrue(data.defects.all { it.severity in 1..5 })
    }
}
