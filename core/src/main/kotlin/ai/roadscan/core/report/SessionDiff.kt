package ai.roadscan.core.report

import ai.roadscan.core.geo.GeoMath
import ai.roadscan.core.model.DefectRecord
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.GeoPoint
import kotlin.math.abs

/** Одно обследование для сравнения: находки + итоги сметы. */
data class SurveySide(
    val defects: List<DefectRecord>,
    val areaM2: Double,
    val cost: Double,
)

/**
 * Сравнение двух обследований одного участка — приёмка ремонта:
 * что устранили, что осталось, что появилось нового.
 *
 * Сопоставление — по геометрии: дефект «остался», если в новом
 * обследовании есть дефект того же типа в пределах [MATCH_RADIUS_M]
 * от старого (маршруты проходят с разными ошибками GPS — совпадения
 * «в лоб» не бывает). Жадное по расстоянию пары: каждая точка —
 * не более одной пары.
 */
data class SessionDelta(
    val defectsBefore: Int,
    val defectsAfter: Int,
    val areaBeforeM2: Double,
    val areaAfterM2: Double,
    val costBefore: Double,
    val costAfter: Double,
    /** Устранено: в новом обследовании дефекта такого типа рядом нет. */
    val resolved: Int?,
    /** Осталось: тот же тип в том же месте. */
    val remaining: Int?,
    /** Появилось новых. */
    val appeared: Int?,
    /** false — у одной из смен нет координат: только агрегаты, без матчинга. */
    val matched: Boolean,
    /** Изменение числа дефектов, %; null — в первом обследовании пусто. */
    val changePercent: Double?,
    /** Структура по типам: тип, было, стало. */
    val byType: List<DefectTypeDelta>,
) {
    /** Смета уменьшилась (деньги) — только если обе сметы заполнены. */
    val costSaved: Double
        get() = costBefore - costAfter
}

data class DefectTypeDelta(
    val type: DefectType,
    val before: Int,
    val after: Int,
)

object SessionDiff {

    /** Радиус сопоставления: погрешность двух проездов с GPS. */
    const val MATCH_RADIUS_M = 5.0

    /**
     * Радиус для проездов без GPS: километраж видео-участка считается
     * из позиции в ролике и грубо ошибается (десятки метров), поэтому
     * 5 м по км склеивали бы разные дефекты — берём 30 м.
     */
    const val MATCH_RADIUS_KM_M = 30.0

    fun of(
        before: SurveySide,
        after: SurveySide,
        radiusM: Double = MATCH_RADIUS_M,
    ): SessionDelta {
        val b = before.defects
        val a = after.defects
        val all = b + a

        // Два честных режима сопоставления: по координатам (GPS) или
        // по километражу (видео-участок без GPS). Смешанный случай —
        // у кого-то нет ни того, ни другого — сопоставление выключено,
        // иначе «устранено/новых» было бы выдумкой по неполным данным.
        val allGeo = all.isNotEmpty() && all.all { it.latitude != null && it.longitude != null }
        val allKm = all.isNotEmpty() && all.all { it.km != null }
        val matched = allGeo || allKm

        var resolved: Int? = null
        var remaining: Int? = null
        var appeared: Int? = null
        if (matched) {
            val limit = if (allGeo) radiusM else MATCH_RADIUS_KM_M
            val pairs = mutableListOf<Triple<Double, DefectRecord, DefectRecord>>()
            b.forEach { old ->
                a.forEach { fresh ->
                    if (fresh.type != old.type) return@forEach
                    val distance = when {
                        allGeo -> GeoMath.distanceMeters(
                            GeoPoint(old.latitude!!, old.longitude!!),
                            GeoPoint(fresh.latitude!!, fresh.longitude!!),
                        )
                        else -> kotlin.math.abs(old.km!! - fresh.km!!) * 1_000.0
                    }
                    if (distance <= limit) pairs += Triple(distance, old, fresh)
                }
            }
            val takenOld = mutableSetOf<String>()
            val takenNew = mutableSetOf<String>()
            pairs.sortedBy { it.first }.forEach { (_, old, fresh) ->
                if (old.id !in takenOld && fresh.id !in takenNew) {
                    takenOld += old.id
                    takenNew += fresh.id
                }
            }
            remaining = takenOld.size
            resolved = b.size - remaining
            appeared = a.size - takenNew.size
        }

        val types = (b.map { it.type } + a.map { it.type }).toSet()
        val byType = types
            .map { type ->
                DefectTypeDelta(
                    type = type,
                    before = b.count { it.type == type },
                    after = a.count { it.type == type },
                )
            }
            .sortedWith(compareByDescending<DefectTypeDelta> { it.after }.thenByDescending { it.before })

        val change = if (b.isEmpty()) {
            null
        } else {
            (a.size - b.size) * 100.0 / b.size
        }

        return SessionDelta(
            defectsBefore = b.size,
            defectsAfter = a.size,
            areaBeforeM2 = before.areaM2,
            areaAfterM2 = after.areaM2,
            costBefore = before.cost,
            costAfter = after.cost,
            resolved = resolved,
            remaining = remaining,
            appeared = appeared,
            matched = matched,
            changePercent = change,
            byType = byType,
        )
    }

    /** Направление изменения для подписи: рост дефектности — хуже. */
    fun trend(delta: SessionDelta): Trend {
        val change = delta.changePercent ?: return Trend.UNKNOWN
        return when {
            abs(change) < 0.5 -> Trend.FLAT
            change > 0 -> Trend.WORSE
            else -> Trend.BETTER
        }
    }

    enum class Trend { BETTER, FLAT, WORSE, UNKNOWN }
}
