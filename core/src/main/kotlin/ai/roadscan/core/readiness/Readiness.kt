package ai.roadscan.core.readiness

/**
 * Готовность комплекта к смене.
 *
 * Перед выездом оператор должен пройти пять пунктов. Раньше это было
 * размазано по экранам, и новичок узнавал о пропущенной калибровке уже в
 * поле. Теперь состояние собирается в один список и показывается на
 * главном экране.
 */
enum class ReadinessStepId {
    PERMISSIONS,
    LICENSE,
    CALIBRATION,
    DOCUMENTS,
    EQUIPMENT,
}

data class ReadinessStep(
    val id: ReadinessStepId,
    /** Готово. */
    val done: Boolean,
    /** Без этого смена невозможна. */
    val blocking: Boolean,
)

data class Readiness(
    val steps: List<ReadinessStep>,
) {
    val blockingPending: Int get() = steps.count { !it.done && it.blocking }
    val pending: Int get() = steps.count { !it.done }

    /** Всё готово к смене. */
    val ready: Boolean get() = pending == 0

    /** Процент готовности — для кольцевого индикатора. */
    val progress: Int
        get() = if (steps.isEmpty()) 0 else (steps.count { it.done } * 100) / steps.size

    fun statusOf(id: ReadinessStepId): Boolean =
        steps.firstOrNull { it.id == id }?.done == true
}

/**
 * Собирает состояние готовности из отдельных проверок.
 *
 * Проверки намеренно разделены: каждая приходит из своего места
 * (разрешения — от системы, лицензия — от хранилища лицензий, калибровка и
 * реквизиты — от профиля, оборудование — от диагностики).
 */
object ReadinessEvaluator {

    fun evaluate(
        permissionsGranted: Boolean,
        licenseActive: Boolean,
        calibrationReady: Boolean,
        documentsFilled: Boolean,
        equipmentReady: Boolean,
        equipmentBlocking: Boolean = true,
    ): Readiness {
        val steps = listOf(
            ReadinessStep(
                id = ReadinessStepId.PERMISSIONS,
                done = permissionsGranted,
                blocking = true,
            ),
            ReadinessStep(
                id = ReadinessStepId.LICENSE,
                done = licenseActive,
                // Без лицензии документы помечаются «ДЕМО» — смена возможна.
                blocking = false,
            ),
            ReadinessStep(
                id = ReadinessStepId.CALIBRATION,
                done = calibrationReady,
                // Без фокусного расстояния площадь считается приблизительно.
                blocking = false,
            ),
            ReadinessStep(
                id = ReadinessStepId.DOCUMENTS,
                done = documentsFilled,
                // Без реквизитов ведомость не подписать — но смена идёт.
                blocking = false,
            ),
            ReadinessStep(
                id = ReadinessStepId.EQUIPMENT,
                done = equipmentReady,
                blocking = equipmentBlocking,
            ),
        )
        return Readiness(steps)
    }
}
