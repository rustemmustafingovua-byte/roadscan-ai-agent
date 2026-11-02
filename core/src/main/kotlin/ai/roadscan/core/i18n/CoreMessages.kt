package ai.roadscan.core.i18n

import ai.roadscan.core.license.Edition

/**
 * Системные сообщения ядра: подсказки оператору, ошибки лицензии и
 * предупреждения калибровки.
 *
 * Держим их вне Android-ресурсов, потому что ядро — чистый Kotlin/JVM: те же
 * строки нужны в тестах, в CLI и в документах. UI берёт свои подписи из
 * `strings.xml`, а голосовые подсказки и ошибки — отсюда, чтобы не было
 * расхождений между озвучкой и текстом на экране.
 */
interface CoreMessages {
    val locale: String

    // --- лицензия ---
    fun edition(edition: Edition): String
    val forever: String
    val licenseFallbackOrganization: String
    fun licenseValid(organization: String, tier: String, until: String): String
    fun trialActive(sessionsLeft: Int, daysLeft: Int): String
    val trialExpired: String
    fun licenseError(code: String): String

    // --- подсказки оператору ---
    val gpsWarning: String
    val batteryWarning: String
    val lensWarning: String
    fun noDiskSpace(freeMb: Long): String
    fun batteryOverheated(tempC: Int): String
    fun thermalThrottle(fps: Int): String
    val thermalEmergency: String
    fun lowDisk(needMb: Long): String

    // --- валидация калибровки ---
    val calibrationHeightError: String
    val calibrationPitchError: String
    val calibrationFocalError: String
    val calibrationDepthBaseError: String
    val calibrationDepthPerGError: String

    object Uk : CoreMessages {
        override val locale = "uk"

        override fun edition(edition: Edition): String = when (edition) {
            Edition.TRIAL -> "Пробний період"
            Edition.PILOT -> "Пілот"
            Edition.PRO -> "Pro"
            Edition.ENTERPRISE -> "Enterprise"
        }

        override val forever = "безстроково"
        override val licenseFallbackOrganization = "Ліцензія"
        override fun licenseValid(organization: String, tier: String, until: String): String =
            "$organization · $tier · до $until"

        override fun trialActive(sessionsLeft: Int, daysLeft: Int): String =
            "Пробний період: $sessionsLeft змін, $daysLeft дн. Документи з водяним знаком."

        override val trialExpired = "Пробний період завершився. Експорт документів заблоковано."

        override fun licenseError(code: String): String = when (code) {
            "FORMAT" -> "Невірний формат ключа"
            "PAYLOAD" -> "Пошкоджене навантаження ключа"
            "SIGNATURE" -> "Пошкоджений підпис"
            "PUBLIC_KEY" -> "Публічний ключ недоступний"
            "SIGNATURE_MISMATCH" -> "Підпис не збігається"
            "UNREADABLE" -> "Ліцензію неможливо прочитати"
            "WRONG_PRODUCT" -> "Ліцензія іншого продукту"
            "EXPIRED" -> "Термін дії ліцензії минув"
            else -> "Ліцензія недійсна"
        }

        override val gpsWarning =
            "Увага, низька точність GPS. Рекомендується підключити зовнішній RTK-модуль."
        override val batteryWarning =
            "Заряд батареї нижче 20%. Рекомендується зберегти путовий лог і завершити зміну."

        override val lensWarning = "Об'єктив забруднений. Протріть камеру."

        override fun noDiskSpace(freeMb: Long): String =
            "Недостатньо місця на диску: $freeMb МБ. Звільніть пам'ять і повторіть."

        override fun batteryOverheated(tempC: Int): String =
            "Батарея перегріта ($tempC °C). Дайте пристрою охолонути."

        override fun thermalThrottle(fps: Int): String =
            "Процесор нагрівся. Знижую частоту аналізу до $fps кадрів за секунду."

        override val thermalEmergency = "Критичний нагрів. Переходжу в режим енергозбереження."

        override fun lowDisk(needMb: Long): String =
            "Мало місця на диску. Звільніть $needMb МБ."

        override val calibrationHeightError = "Висота камери має бути 0.4–3.5 м"
        override val calibrationPitchError = "Нахил камери має бути -5…45°"
        override val calibrationFocalError = "Фокусна відстань має бути 200–4000 px"
        override val calibrationDepthBaseError = "Базова глибина не може бути від'ємною"
        override val calibrationDepthPerGError = "Коефіцієнт глибини на 1 g має бути > 0"
    }

    object Ru : CoreMessages {
        override val locale = "ru"

        override fun edition(edition: Edition): String = when (edition) {
            Edition.TRIAL -> "Пробный период"
            Edition.PILOT -> "Пилот"
            Edition.PRO -> "Pro"
            Edition.ENTERPRISE -> "Enterprise"
        }

        override val forever = "бессрочно"
        override val licenseFallbackOrganization = "Лицензия"
        override fun licenseValid(organization: String, tier: String, until: String): String =
            "$organization · $tier · до $until"

        override fun trialActive(sessionsLeft: Int, daysLeft: Int): String =
            "Пробный период: $sessionsLeft смен, $daysLeft дн. Документы с водяным знаком."

        override val trialExpired = "Пробный период закончился. Экспорт документов заблокирован."

        override fun licenseError(code: String): String = when (code) {
            "FORMAT" -> "Неверный формат ключа"
            "PAYLOAD" -> "Повреждённая полезная нагрузка"
            "SIGNATURE" -> "Повреждённая подпись"
            "PUBLIC_KEY" -> "Публичный ключ недоступен"
            "SIGNATURE_MISMATCH" -> "Подпись не совпадает"
            "UNREADABLE" -> "Нечитаемая лицензия"
            "WRONG_PRODUCT" -> "Лицензия другого продукта"
            "EXPIRED" -> "Срок действия лицензии истёк"
            else -> "Лицензия недействительна"
        }

        override val gpsWarning =
            "Внимание, низкая точность GPS. Рекомендуется подключить внешний RTK-модуль."
        override val batteryWarning =
            "Заряд батареи ниже 20%. Рекомендуется сохранить путевой лог и завершить смену."

        override val lensWarning = "Объектив загрязнен. Протрите камеру."

        override fun noDiskSpace(freeMb: Long): String =
            "Недостаточно места на диске: $freeMb МБ. Освободите память и повторите."

        override fun batteryOverheated(tempC: Int): String =
            "Батарея перегрета ($tempC °C). Дайте устройству остыть."

        override fun thermalThrottle(fps: Int): String =
            "Процессор нагрелся. Снижаю частоту анализа до $fps кадров в секунду."

        override val thermalEmergency = "Критический нагрев. Перехожу в энергосберегающий режим."

        override fun lowDisk(needMb: Long): String =
            "Мало места на диске. Освободите $needMb МБ."

        override val calibrationHeightError = "Высота камеры должна быть 0.4–3.5 м"
        override val calibrationPitchError = "Наклон камеры должен быть -5…45°"
        override val calibrationFocalError = "Фокусное расстояние должно быть 200–4000 px"
        override val calibrationDepthBaseError = "Базовая глубина не может быть отрицательной"
        override val calibrationDepthPerGError = "Коэффициент глубины на 1 g должен быть > 0"
    }

    companion object {
        val DEFAULT: CoreMessages = Uk

        fun of(language: String?): CoreMessages = when (language?.lowercase()?.take(2)) {
            "ru" -> Ru
            else -> Uk
        }
    }
}
