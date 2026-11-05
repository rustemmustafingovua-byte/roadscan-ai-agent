package ai.roadscan.core.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportMetaSignatureTest {

    @Test
    fun `подпись переживает json-круговой проход`() {
        val meta = ReportMeta.EMPTY.copy(
            operatorName = "Іванов",
            signaturePng = "iVBORw0KGgo=",
        )
        val restored = ReportMeta.fromJson(ReportMeta.toJson(meta))
        assertEquals("iVBORw0KGgo=", restored.signaturePng)
        assertEquals("Іванов", restored.operatorName)
    }

    @Test
    fun `старый json без поля подписи даёт null`() {
        // Файлы прошлых версий не содержат поля — ignoreUnknownKeys +
        // default value обязаны дать пустую подпись, а не падение.
        val restored = ReportMeta.fromJson("""{"objectName":"ул. Ленина"}""")
        assertNull(restored.signaturePng)
        assertEquals("ул. Ленина", restored.objectName)
        assertEquals(ReportMeta.EMPTY, ReportMeta.fromJson("не json"))
    }
}
