package ai.roadscan.core.i18n

import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectType

/**
 * Тексты документов (PDF/Excel) и подписей отчёта.
 *
 * Дорожная служба принимает ведомость только на своём языке и в своей
 * валюте, поэтому строки вынесены из генераторов в отдельные реализации:
 * `DocStrings.Uk` — Украина (грн, ДСТУ-термінологія), `DocStrings.Ru` — РФ.
 * GeoJSON при этом остаётся с англоязычными ключами (стандарт для ГИС),
 * локализуется только подпись типа дефекта.
 */
interface DocStrings {
    val locale: String

    // --- шапка и подписи ---
    val reportTitle: String
    /** Заголовок раздела со снимками находок. */
    val photosTitle: String
    val sessionLabel: String
    val objectLabel: String
    val roadLabel: String
    val customerLabel: String
    val contractorLabel: String
    val contractLabel: String
    val operatorLabel: String
    val vehicleLabel: String
    val weatherLabel: String

    // --- сводка ---
    val summaryTitle: String
    val areaLabel: String
    val millingLabel: String
    val asphaltLabel: String
    val estimateLabel: String
    val confirmedLabel: String
    val visualOnlyLabel: String
    val clustersLabel: String
    /** Единица счёта для числа дефектов (не кластеров). */
    val defectsCountLabel: String
    /** Единица счёта для числа зон повреждения (кластеров). */
    val zonesCountLabel: String

    // --- смета ---
    val estimateTitle: String
    val codeColumn: String
    val nameColumn: String
    val unitColumn: String
    val quantityColumn: String
    val priceColumn: String
    val amountColumn: String
    val subtotalLabel: String
    val vatLabel: String
    val totalLabel: String
    val totalWithVat: String

    // --- ведомость кластеров ---
    val registerTitle: String
    val idColumn: String
    val typeColumn: String
    val statusColumn: String
    val maxDepthColumn: String
    val avgDepthColumn: String
    val severityColumn: String
    val countColumn: String
    val latitudeColumn: String
    val longitudeColumn: String
    /** Колонка километража (обследование без GPS). */
    val kmColumn: String
    /** Колонка с количеством дефектов в зоне. */
    val sectionColumn: String
    val statusConfirmed: String
    val statusVisual: String
    val visualOnlyNote: String
    val visualOnlySection: String

    // --- метрики / служебное ---
    val metricsTitle: String
    val metricColumn: String
    val valueColumn: String
    val sessionLabelShort: String
    val startLabel: String
    val endLabel: String
    val currencyLabel: String
    val calibrationLabel: String
    val calibrationEstimate: String
    val demoNotice: String
    val demoWatermark: String
    val footer: String

    /** Символ валюты по ISO-коду каталога. */
    fun currencySymbol(currency: String): String

    /** Название типа дефекта для людей. */
    fun defectType(type: DefectType): String

    /** Статус подтверждения для людей. */
    fun confirmation(status: ConfirmationStatus): String

    /** Названия листов Excel. */
    val sheetEstimate: String
    val sheetRegister: String
    val sheetDefects: String
    val sheetMetrics: String

    object Uk : DocStrings {
        override val locale = "uk"

        override val reportTitle = "Відомість дефектів RoadScan AI"
        override val photosTitle = "ФОТО НАХОДОК"
        override val sessionLabel = "Сесія"
        override val objectLabel = "Об'єкт"
        override val roadLabel = "Дорога, км"
        override val customerLabel = "Замовник"
        override val contractorLabel = "Підрядник"
        override val contractLabel = "Договір"
        override val operatorLabel = "Оператор"
        override val vehicleLabel = "ТЗ"
        override val weatherLabel = "Погода"

        override val summaryTitle = "Зведення"
        override val areaLabel = "Площа"
        override val millingLabel = "Фрезерування"
        override val asphaltLabel = "Асфальтобетон"
        override val estimateLabel = "Кошторис"
        override val confirmedLabel = "Підтверджено"
        override val visualOnlyLabel = "Лише візуально"
        override val clustersLabel = "Кластерів"
        override val defectsCountLabel = "Дефектів"
        override val zonesCountLabel = "Зон пошкодження"

        override val estimateTitle = "Кошторис"
        override val codeColumn = "Код"
        override val nameColumn = "Найменування"
        override val unitColumn = "Од."
        override val quantityColumn = "К-сть"
        override val priceColumn = "Ціна"
        override val amountColumn = "Сума"
        override val subtotalLabel = "Разом без ПДВ"
        override val vatLabel = "ПДВ"
        override val totalLabel = "ВСЬОГО"
        override val totalWithVat = "ВСЬОГО (з ПДВ)"

        override val registerTitle = "Відомість кластерів"
        override val idColumn = "ID"
        override val typeColumn = "Тип"
        override val statusColumn = "Статус"
        override val maxDepthColumn = "Глиб. макс"
        override val avgDepthColumn = "Глиб. сер."
        override val severityColumn = "Критичність"
        override val countColumn = "К-сть"
        override val latitudeColumn = "Широта"
        override val longitudeColumn = "Довгота"
        override val kmColumn = "Км"
        override val sectionColumn = "Деф."
        override val statusConfirmed = "підтверджено"
        override val statusVisual = "лише візуально"
        override val visualOnlyNote =
            "Кластери «лише візуально» до кошторису не включені: немає підтвердження ударом підвіски."
        override val visualOnlySection =
            "Кандидати без фізичного підтвердження (до кошторису не включені)"

        override val metricsTitle = "Метрики сесії"
        override val metricColumn = "Показник"
        override val valueColumn = "Значення"
        override val sessionLabelShort = "Сесія"
        override val startLabel = "Початок"
        override val endLabel = "Завершення"
        override val currencyLabel = "Валюта"
        override val calibrationLabel = "Калібрування"
        override val calibrationEstimate = "оцінка"
        override val demoNotice = "ДЕМОНСТРАЦІЙНИЙ ДОКУМЕНТ — не для оплати"
        override val demoWatermark = "ДЕМО"
        override val footer = "RoadScan AI Agent · стор."

        override fun currencySymbol(currency: String): String = when (currency.uppercase()) {
            "UAH" -> "₴"
            "USD" -> "$"
            "EUR" -> "€"
            else -> "₴"
        }

        override fun defectType(type: DefectType): String = when (type) {
            DefectType.POTHOLE -> "Вибій"
            DefectType.CRACK_NETWORK -> "Сітка тріщин"
            DefectType.RUTTING -> "Колійність"
            DefectType.PATCH -> "Латка"
            DefectType.MANHOLE_COVER -> "Люк"
            DefectType.UNKNOWN -> "Невідомо"
        }

        override fun confirmation(status: ConfirmationStatus): String = when (status) {
            ConfirmationStatus.CONFIRMED_PHYSICAL -> statusConfirmed
            ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY -> statusVisual
        }

        override val sheetEstimate = "Кошторис"
        override val sheetRegister = "Відомість"
        override val sheetDefects = "Дефекти"
        override val sheetMetrics = "Метрики"
    }

    object Ru : DocStrings {
        override val locale = "ru"

        override val reportTitle = "Ведомость дефектов RoadScan AI"
        override val photosTitle = "ФОТО НАХОДОК"
        override val sessionLabel = "Сессия"
        override val objectLabel = "Объект"
        override val roadLabel = "Дорога, км"
        override val customerLabel = "Заказчик"
        override val contractorLabel = "Подрядчик"
        override val contractLabel = "Договор"
        override val operatorLabel = "Оператор"
        override val vehicleLabel = "ТС"
        override val weatherLabel = "Погода"

        override val summaryTitle = "Сводка"
        override val areaLabel = "Площадь"
        override val millingLabel = "Фрезеровка"
        override val asphaltLabel = "Асфальтобетон"
        override val estimateLabel = "Смета"
        override val confirmedLabel = "Подтверждено"
        override val visualOnlyLabel = "Visual-only"
        override val clustersLabel = "Кластеров"
        override val defectsCountLabel = "Дефектов"
        override val zonesCountLabel = "Зон повреждения"

        override val estimateTitle = "Смета"
        override val codeColumn = "Код"
        override val nameColumn = "Наименование"
        override val unitColumn = "Ед."
        override val quantityColumn = "Кол-во"
        override val priceColumn = "Цена"
        override val amountColumn = "Сумма"
        override val subtotalLabel = "Итого без НДС"
        override val vatLabel = "НДС"
        override val totalLabel = "ВСЕГО"
        override val totalWithVat = "ВСЕГО (с НДС)"

        override val registerTitle = "Ведомость кластеров"
        override val idColumn = "ID"
        override val typeColumn = "Тип"
        override val statusColumn = "Статус"
        override val maxDepthColumn = "Глуб. макс"
        override val avgDepthColumn = "Глуб. сред."
        override val severityColumn = "Критичность"
        override val countColumn = "Кол-во"
        override val latitudeColumn = "Широта"
        override val longitudeColumn = "Долгота"
        override val kmColumn = "Км"
        override val sectionColumn = "Деф."
        override val statusConfirmed = "confirmed"
        override val statusVisual = "visual-only"
        override val visualOnlyNote =
            "Visual-only кластеры в смету не включены (нет удара подвески)."
        override val visualOnlySection =
            "Кандидаты без физического подтверждения (в смету не включены)"

        override val metricsTitle = "Метрики сессии"
        override val metricColumn = "Показатель"
        override val valueColumn = "Значение"
        override val sessionLabelShort = "Сессия"
        override val startLabel = "Начало"
        override val endLabel = "Окончание"
        override val currencyLabel = "Валюта"
        override val calibrationLabel = "Калибровка"
        override val calibrationEstimate = "оценка"
        override val demoNotice = "ДЕМОНСТРАЦИОННЫЙ ДОКУМЕНТ — не для оплаты"
        override val demoWatermark = "ДЕМО"
        override val footer = "RoadScan AI Agent · стр."

        override fun currencySymbol(currency: String): String = when (currency.uppercase()) {
            "RUB" -> "₽"
            "UAH" -> "₴"
            "USD" -> "$"
            "EUR" -> "€"
            else -> "₽"
        }

        override fun defectType(type: DefectType): String = when (type) {
            DefectType.POTHOLE -> "Яма"
            DefectType.CRACK_NETWORK -> "Сеть трещин"
            DefectType.RUTTING -> "Колея"
            DefectType.PATCH -> "Заплатка"
            DefectType.MANHOLE_COVER -> "Люк"
            DefectType.UNKNOWN -> "Неизвестно"
        }

        override fun confirmation(status: ConfirmationStatus): String = when (status) {
            ConfirmationStatus.CONFIRMED_PHYSICAL -> statusConfirmed
            ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY -> statusVisual
        }

        override val sheetEstimate = "Смета"
        override val sheetRegister = "Ведомость"
        override val sheetDefects = "Дефекты"
        override val sheetMetrics = "Метрики"
    }

    companion object {
        val DEFAULT: DocStrings = Uk

        fun of(language: String?): DocStrings = when (language?.lowercase()?.take(2)) {
            "ru" -> Ru
            "uk", "ua" -> Uk
            else -> Uk
        }
    }
}
