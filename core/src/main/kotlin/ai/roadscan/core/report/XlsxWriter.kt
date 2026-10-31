package ai.roadscan.core.report

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Минимальный генератор XLSX (OOXML) без Apache POI.
 *
 * Поддерживает несколько листов, inline-строки, числа, «жирную» строку
 * заголовка и ширину колонок. Такой файл открывается Excel/LibreOffice/Google
 * Sheets и весит килобайты, что важно для офлайн-выгрузки в поле.
 */
class XlsxWriter {

    data class Sheet(
        val name: String,
        val rows: List<List<Any?>>,
        val headerRowCount: Int = 0,
        val columnWidths: List<Int> = emptyList(),
        val boldRows: Set<Int> = emptySet(),
    )

    fun write(sheets: List<Sheet>): ByteArray {
        require(sheets.isNotEmpty()) { "At least one sheet required" }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(contentTypes(sheets.size).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(ROOT_RELS.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(workbook(sheets).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(workbookRels(sheets.size).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            zip.write(STYLES.toByteArray())
            zip.closeEntry()

            sheets.forEachIndexed { index, sheet ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${index + 1}.xml"))
                zip.write(worksheet(sheet).toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..sheetCount) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun workbook(sheets: List<Sheet>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("<sheets>")
        sheets.forEachIndexed { index, sheet ->
            val name = escape(sanitizeName(sheet.name))
            append("""<sheet name="$name" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..sheetCount) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${sheetCount + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun worksheet(sheet: Sheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")

        if (sheet.columnWidths.isNotEmpty()) {
            append("<cols>")
            sheet.columnWidths.forEachIndexed { i, width ->
                append("""<col min="${i + 1}" max="${i + 1}" width="$width" customWidth="1"/>""")
            }
            append("</cols>")
        }

        append("<sheetData>")
        sheet.rows.forEachIndexed { r, row ->
            val rowNumber = r + 1
            append("""<row r="$rowNumber">""")
            row.forEachIndexed { c, value ->
                if (value != null) {
                    append(cell(ref(c, rowNumber), value, r < sheet.headerRowCount || r in sheet.boldRows))
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun cell(ref: String, value: Any?, bold: Boolean): String {
        val style = if (bold) """ s="1"""" else ""
        return when (value) {
            is Number -> """<c r="$ref"$style><v>$value</v></c>"""
            is Boolean -> """<c r="$ref"$style t="b"><v>${if (value) 1 else 0}</v></c>"""
            else -> """<c r="$ref"$style t="inlineStr"><is><t xml:space="preserve">${escape(value.toString())}</t></is></c>"""
        }
    }

    private fun ref(columnIndex: Int, row: Int): String = "${columnName(columnIndex)}$row"

    private fun columnName(index: Int): String {
        var n = index + 1
        val name = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            name.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return name.toString()
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("""[\\/*?:\[\]]"""), "_").take(31).ifBlank { "Sheet" }

    private fun escape(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\t') append(ch)
            }
        }
    }

    companion object {
        private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

        private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2">
<font><sz val="11"/><name val="Calibri"/></font>
<font><b/><sz val="11"/><name val="Calibri"/></font>
</fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
    }
}
