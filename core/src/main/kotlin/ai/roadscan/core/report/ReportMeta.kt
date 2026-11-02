package ai.roadscan.core.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Реквизиты работ, которые попадают в шапку PDF/Excel/GeoJSON.
 *
 * Дорожной службе недостаточно «файла с цифрами»: ведомость должна быть
 * привязана к объекту, заказчику и договору, иначе её нельзя ни согласовать,
 * ни оплатить. Поля необязательные, но в коммерческой поставке заполняются
 * один раз в профиле организации и переиспользуются во всех сменах.
 */
@Serializable
data class ReportMeta(
    /** Наименование объекта/участка, например «ул. Ленина, чётная сторона». */
    val objectName: String = "",
    /** Дорога и километраж: «А-108, км 12+400 — 13+100». */
    val roadName: String = "",
    /** Заказчик (дорожная служба, администрация). */
    val customer: String = "",
    /** Подрядчик/исполнитель обследования. */
    val contractor: String = "",
    val contractNumber: String = "",
    /** Оператор, проводивший смену. */
    val operatorName: String = "",
    /** Транспортное средство и госномер. */
    val vehicle: String = "",
    val weather: String = "",
    /** Демонстрационный режим: документы помечаются водяным знаком. */
    val demo: Boolean = false,
) {
    val isFilled: Boolean
        get() = objectName.isNotBlank() || roadName.isNotBlank() || customer.isNotBlank() ||
            contractor.isNotBlank() || contractNumber.isNotBlank()

    /** Строка «Объект · Дорога» для заголовков; пустая, если ничего не задано. */
    fun headline(): String = listOf(objectName, roadName)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    fun isEmpty(): Boolean = this == EMPTY

    companion object {
        val EMPTY = ReportMeta()

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String): ReportMeta = runCatching {
            json.decodeFromString<ReportMeta>(text)
        }.getOrDefault(EMPTY)

        fun toJson(meta: ReportMeta): String = json.encodeToString(meta)
    }
}
