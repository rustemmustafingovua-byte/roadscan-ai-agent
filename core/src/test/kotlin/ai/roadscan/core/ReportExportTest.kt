package ai.roadscan.core

import ai.roadscan.core.agent.PostProcessingAgent
import ai.roadscan.core.geo.GeoMath
import ai.roadscan.core.report.GeoJsonExporter
import ai.roadscan.core.report.ReportXlsxBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportExportTest {

    private fun report() = PostProcessingAgent().process(
        sessionId = "SES-1",
        startedAtMs = 1_000_000L,
        endedAtMs = 1_600_000L,
        defects = listOf(
            Fixtures.defect(location = Fixtures.ORIGIN, areaM2 = 1.2, depthMm = 50.0),
            Fixtures.defect(location = GeoMath.offset(Fixtures.ORIGIN, 1.0, 0.0), areaM2 = 1.1, depthMm = 48.0),
            Fixtures.defect(location = GeoMath.offset(Fixtures.ORIGIN, 0.5, 1.0), areaM2 = 1.0, depthMm = 46.0),
            Fixtures.defect(
                location = GeoMath.offset(Fixtures.ORIGIN, 2.0, 0.0),
                type = ai.roadscan.core.model.DefectType.CRACK_NETWORK,
                areaM2 = 0.6,
                depthMm = 20.0,
                confirmed = false,
            ),
        ),
        stats = ai.roadscan.core.agent.SessionStats(),
    )

    @Test
    fun `geojson is valid and contains confirmed and visual clusters`() {
        val json = Json.parseToJsonElement(GeoJsonExporter.export(report())).jsonObject

        assertEquals("FeatureCollection", json["type"]!!.jsonPrimitive.content)
        val features = json["features"]!!.jsonArray
        assertEquals(2, features.size)
        val statuses = features.map {
            it.jsonObject["properties"]!!.jsonObject["status"]!!.jsonPrimitive.content
        }
        assertTrue(statuses.contains("CONFIRMED_PHYSICAL"))
        assertTrue(statuses.contains("UNCONFIRMED_VISUAL_ONLY"))
    }

    @Test
    fun `geojson polygon coordinates are lon lat and closed`() {
        val json = Json.parseToJsonElement(GeoJsonExporter.export(report())).jsonObject
        val feature = json["features"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content == "Polygon" }
        val ring = feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
        assertTrue(ring.size >= 4)
        val first = ring.first().jsonArray
        val last = ring.last().jsonArray
        assertEquals(first[0].jsonPrimitive.content, last[0].jsonPrimitive.content)
    }

    @Test
    fun `xlsx has required parts and valid xml`() {
        val bytes = ReportXlsxBuilder.build(report())
        val entries = unzip(bytes)

        assertTrue(entries.keys.contains("[Content_Types].xml"))
        assertTrue(entries.keys.contains("xl/workbook.xml"))
        assertTrue(entries.keys.contains("xl/worksheets/sheet1.xml"))

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        for ((name, data) in entries) {
            builder.parse(ByteArrayInputStream(data))
            assertTrue(name.isNotEmpty())
        }

        val sheet = entries["xl/worksheets/sheet1.xml"]!!.decodeToString()
        assertTrue(sheet.contains("Фрезерування"))
        assertTrue(sheet.contains("ASPHALT"))
    }

    @Test
    fun `xlsx generation is under three seconds`() {
        val defects = (0 until 2_000).map { i ->
            Fixtures.defect(
                location = GeoMath.offset(Fixtures.ORIGIN, i * 1.0, 0.0),
                areaM2 = 0.5,
                depthMm = 35.0,
            )
        }
        val large = PostProcessingAgent().process("SES-P", 0, 1_000, defects, ai.roadscan.core.agent.SessionStats())
        val started = System.nanoTime()
        val bytes = ReportXlsxBuilder.build(large)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0

        assertTrue(bytes.size > 1_000)
        assertTrue(elapsedMs < 3_000, "xlsx took $elapsedMs ms")
    }

    @Test
    fun `estimate totals appear in workbook`() {
        val r = report()
        val bytes = ReportXlsxBuilder.build(r)
        val sheet = unzip(bytes)["xl/worksheets/sheet1.xml"]!!.decodeToString()
        assertTrue(sheet.contains("ВСЬОГО"))
        assertTrue(sheet.contains("Асфальтобетон"))
    }

    @Test
    fun `work order details and demo watermark reach documents`() {
        val meta = ai.roadscan.core.report.ReportMeta(
            objectName = "ул. Ленина, чётная сторона",
            roadName = "А-108, км 12+400",
            customer = "МБУ Дорремстрой",
            contractor = "ООО ДорСкан",
            contractNumber = "44-2026/17",
            demo = true,
        )
        val base = report()
        val report = base.copy(meta = meta)

        val sheet = unzip(ReportXlsxBuilder.build(report))["xl/worksheets/sheet1.xml"]!!.decodeToString()
        assertTrue(sheet.contains("ул. Ленина"))
        assertTrue(sheet.contains("МБУ Дорремстрой"))
        assertTrue(sheet.contains("ДЕМОНСТРАЦІЙНИЙ"))

        val geojson = Json.parseToJsonElement(GeoJsonExporter.export(report)).jsonObject
        val props = geojson["properties"]!!.jsonObject
        assertEquals("А-108, км 12+400", props["road_name"]!!.jsonPrimitive.content)
        assertEquals("true", props["demo"]!!.jsonPrimitive.content)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }
}
