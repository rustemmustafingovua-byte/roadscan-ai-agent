package ai.roadscan.core

import ai.roadscan.core.report.PackageBuilder
import ai.roadscan.core.report.PackageFile
import ai.roadscan.core.report.ReportMeta
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackageBuilderTest {

    private val files = listOf(
        PackageFile("ВЕДОМІСТЬ.pdf", "pdf".toByteArray(), "Ведомость дефектов"),
        PackageFile("КОШТОРИС.xlsx", "xlsx".toByteArray(), "Смета"),
        PackageFile("ОБ'ЄКТ.geojson", "geojson".toByteArray(), "Геоданные для ГИС"),
    )

    @Test
    fun `package contains manifest and all files`() {
        val zip = PackageBuilder.build(
            sessionId = "SES-123",
            meta = ReportMeta(objectName = "вул. Леніна", roadName = "М-06"),
            files = files,
            generatedAtMs = 1_700_000_000_000,
        )

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                entries[entry.name] = stream.readBytes().toString(Charsets.UTF_8)
            }
        }

        assertEquals(4, entries.size, "манифест + три файла")
        assertNotNull(entries["MANIFEST.txt"])
        assertNotNull(entries["SES-123/ВЕДОМІСТЬ.pdf"])
        assertNotNull(entries["SES-123/КОШТОРИС.xlsx"])
        assertNotNull(entries["SES-123/ОБ'ЄКТ.geojson"])
    }

    @Test
    fun `manifest lists requisites and checksums`() {
        val manifest = PackageBuilder.buildManifest(
            sessionId = "SES-123",
            meta = ReportMeta(
                objectName = "вул. Леніна",
                customer = "Служба доріг",
                contractNumber = "24/ДБ-118",
            ),
            files = files,
            generatedAtMs = 1_700_000_000_000,
        )

        assertTrue(manifest.contains("SES-123"))
        assertTrue(manifest.contains("вул. Леніна"))
        assertTrue(manifest.contains("Служба доріг"))
        assertTrue(manifest.contains("24/ДБ-118"))
        for (file in files) {
            assertTrue(manifest.contains(file.name), "нет файла в манифесте: ${file.name}")
            assertTrue(
                manifest.contains(PackageBuilder.sha256(file.content)),
                "нет контрольной суммы для ${file.name}",
            )
        }
    }

    @Test
    fun `demo package is marked as not a financial document`() {
        val manifest = PackageBuilder.buildManifest(
            sessionId = "SES-demo",
            meta = ReportMeta(demo = true),
            files = files,
            generatedAtMs = 1_700_000_000_000,
        )

        assertTrue(manifest.contains("ДЕМО"))
    }

    @Test
    fun `file names are sanitized for archive`() {
        val zip = PackageBuilder.build(
            sessionId = "SES/1",
            meta = ReportMeta.EMPTY,
            files = listOf(PackageFile("a/b:c.pdf", byteArrayOf(1, 2))),
            generatedAtMs = 0,
        )

        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                names += entry.name
            }
        }
        assertTrue(names.none { it.contains("/a/b:c.pdf") }, "имя должно быть очищено: $names")
        assertTrue(names.any { it.endsWith("a_b_c.pdf") }, "ожидалось очищенное имя: $names")
    }
}
