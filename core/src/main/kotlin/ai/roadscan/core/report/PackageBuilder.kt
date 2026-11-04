package ai.roadscan.core.report

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Файл в составе комплекта. */
data class PackageFile(
    /** Имя файла внутри архива. */
    val name: String,
    val content: ByteArray,
    /** Назначение файла — попадает в манифест. */
    val role: String = "",
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PackageFile && name == other.name)

    override fun hashCode(): Int = name.hashCode()
}

/**
 * Сборка комплекта документов по объекту.
 *
 * Дорожной службе нужен не набор файлов, а один передаваемый пакет: ведомость,
 * смета, геоданные, манифест с реквизитами и контрольными суммами. Манифест
 * фиксирует состав пакета — при передаче получатель может проверить, что
 * ничего не потерялось и не подменилось.
 */
object PackageBuilder {

    /**
     * Собирает ZIP-комплект.
     *
     * @param sessionId идентификатор смены (имя папки внутри архива)
     * @param meta реквизиты объекта — попадают в манифест
     * @param files содержимое документов
     * @param generatedAtMs время формирования (в манифесте)
     */
    fun build(
        sessionId: String,
        meta: ReportMeta = ReportMeta.EMPTY,
        files: List<PackageFile>,
        generatedAtMs: Long = System.currentTimeMillis(),
        manifestName: String = "MANIFEST.txt",
    ): ByteArray {
        require(files.isNotEmpty()) { "комплект пуст" }
        val manifest = buildManifest(sessionId, meta, files, generatedAtMs)

        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            val manifestEntry = ZipEntry(manifestName)
            zip.putNextEntry(manifestEntry)
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (file in files) {
                val entry = ZipEntry("${sanitize(sessionId)}/${sanitize(file.name)}")
                zip.putNextEntry(entry)
                zip.write(file.content)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    /** Текст манифеста: состав, реквизиты, размеры и SHA-256 каждого файла. */
    fun buildManifest(
        sessionId: String,
        meta: ReportMeta,
        files: List<PackageFile>,
        generatedAtMs: Long,
    ): String {
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(generatedAtMs))
        return buildString {
            appendLine("ROADSCAN AI AGENT — КОМПЛЕКТ ОБСЛЕДОВАНИЯ")
            appendLine("=".repeat(60))
            appendLine("Смена:          $sessionId")
            appendLine("Сформовано:     $stamp")
            appendLine()
            appendLine("РЕКВИЗИТЫ")
            appendLine("-".repeat(60))
            appendLine("Об'єкт:        ${meta.objectName.ifBlank { "—" }}")
            appendLine("Дорога:        ${meta.roadName.ifBlank { "—" }}")
            appendLine("Замовник:      ${meta.customer.ifBlank { "—" }}")
            appendLine("Підрядник:     ${meta.contractor.ifBlank { "—" }}")
            appendLine("Договір:       ${meta.contractNumber.ifBlank { "—" }}")
            appendLine("Оператор:      ${meta.operatorName.ifBlank { "—" }}")
            appendLine("Транспорт:     ${meta.vehicle.ifBlank { "—" }}")
            appendLine("Погода:        ${meta.weather.ifBlank { "—" }}")
            if (meta.demo) {
                appendLine()
                appendLine("ДОКУМЕНТИ ПОЗНАЧЕНО ВОДЯНИМ ЗНАКОМ «ДЕМО» — НЕ Є ФІНАНСОВИМ ДОКУМЕНТОМ")
            }
            appendLine()
            appendLine("СКЛАД КОМПЛЕКТУ")
            appendLine("-".repeat(60))
            for (file in files) {
                appendLine("${file.name}")
                if (file.role.isNotBlank()) appendLine("   ${file.role}")
                appendLine("   розмір: ${file.content.size} байт")
                appendLine("   sha256: ${sha256(file.content)}")
            }
            appendLine()
            appendLine("Перевірка: sha256Sum файлу зі списку має збігатися з рядком вище.")
            appendLine("RoadScan AI Agent · усі дані зібрані офлайн на пристрої")
        }
    }

    fun sha256(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "file" }
}
