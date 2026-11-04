package ai.roadscan.core.geo

import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectRecord
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.GeoPoint
import kotlin.math.sqrt

data class DefectCluster(
    val id: String,
    val type: DefectType,
    val center: GeoPoint,
    val polygon: List<GeoPoint>,
    val areaM2: Double,
    val maxDepthMm: Double,
    val avgDepthMm: Double,
    val maxSeverity: Int,
    val defectCount: Int,
    val status: ConfirmationStatus,
    /** Километраж зоны по оси дороги (для обследований без GPS). */
    val km: Double? = null,
    /** Координат нет — зона задана только километражом. */
    val hasGeometry: Boolean = true,
) {
    val confirmed: Boolean get() = status == ConfirmationStatus.CONFIRMED_PHYSICAL
}

/**
 * Кластеризация точечных дефектов в полигональные зоны (DBSCAN на локальной ENU-плоскости).
 *
 * Сеть трещин на 5 м превращается в один полигон: eps=2.5 м даёт цепочку
 * соседних точек, выпуклая оболочка + буфер по среднему радиусу дефекта дают
 * площадь зоны, а не сумму площадей отдельных ям.
 */
class DefectClusterer(
    private val config: AgentConfig = AgentConfig.DEFAULT,
) {
    fun cluster(defects: List<DefectRecord>): List<DefectCluster> {
        if (defects.isEmpty()) return emptyList()

        // Отклонённый оператором дефект — ложное срабатывание, в ведомость
        // и смету он не попадает.
        val live = defects.filter { it.review != ai.roadscan.core.model.ReviewDecision.REJECTED }
        if (live.isEmpty()) return emptyList()

        // Дефекты с координатами кластеризуются по геометрии (DBSCAN в ENU).
        val geolocated = live.filter { it.location != null }
        // Без координат (обследование видео без привязки к точке) группируем
        // по километражу: соседние километры — одна зона повреждения.
        val kilometerOnly = live.filter { it.location == null }

        val result = mutableListOf<DefectCluster>()
        if (geolocated.isNotEmpty()) {
            val frame = GeoMath.LocalFrame(geolocated.first().location!!)
            result += geolocated.groupBy { it.type }.flatMap { (type, group) ->
                dbscan(group, frame).mapIndexed { index, members ->
                    toCluster(type, members, frame, index)
                }
            }
        }
        result += clusterByKilometer(kilometerOnly)
        return result.sortedByDescending { it.areaM2 }
    }

    /**
     * Кластеризация дефектов без координат: по километражу, с допуском
     * [kmMergeGapM]. Используется для видеообследований, где GPS нет, но
     * километраж участка задан оператором.
     */
    private fun clusterByKilometer(defects: List<DefectRecord>): List<DefectCluster> {
        if (defects.isEmpty()) return emptyList()
        return defects.groupBy { it.type }.flatMap { (type, group) ->
            group.sortedBy { it.km ?: it.timestampMs.toDouble() }
                .fold(mutableListOf<List<DefectRecord>>()) { acc, defect ->
                    val last = acc.lastOrNull()
                    val lastKm = last?.firstOrNull()?.km
                    val near = lastKm != null && defect.km != null &&
                        kotlin.math.abs(defect.km - lastKm) <= kmMergeGapM
                    if (near) acc[acc.lastIndex] = last!! + defect else acc += mutableListOf(defect)
                    acc
                }
                .mapIndexed { index, members ->
                    val kms = members.mapNotNull { it.km }
                    val area = members.sumOf { it.estimatedAreaM2 }.coerceAtLeast(0.01)
                    val depths = members.filter { it.effectiveConfirmed }.map { it.estimatedDepthMm }
                        .ifEmpty { members.map { it.estimatedDepthMm } }
                    DefectCluster(
                        id = "CL-${type.name.take(2)}-$index-KM${members.first().id.takeLast(6)}",
                        type = type,
                        center = GeoPoint(0.0, 0.0),
                        polygon = emptyList(),
                        areaM2 = area,
                        maxDepthMm = depths.maxOrNull() ?: 0.0,
                        avgDepthMm = depths.average().takeIf { !it.isNaN() } ?: 0.0,
                        maxSeverity = members.maxOf { it.severity },
                        defectCount = members.size,
                        status = if (members.any { it.effectiveConfirmed }) {
                            ConfirmationStatus.CONFIRMED_PHYSICAL
                        } else {
                            ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY
                        },
                        km = kms.average().takeIf { kms.isNotEmpty() },
                        hasGeometry = false,
                    )
                }
        }
    }

    private companion object {
        /** Сколько километров считать одной зоной повреждения. */
        const val kmMergeGapM = 0.05
    }

    private fun dbscan(
        points: List<DefectRecord>,
        frame: GeoMath.LocalFrame,
    ): List<List<DefectRecord>> {
        val locations = points.map { frame.toLocal(it.location!!) }
        val eps = config.clusterEpsM.coerceAtLeast(0.5)
        val minPts = config.clusterMinPoints.coerceAtLeast(1)

        // Пространственный хэш: соседи ищутся по 3×3 ячейкам, а не по всем точкам.
        val grid = HashMap<Long, MutableList<Int>>()
        fun cellKey(cx: Int, cy: Int): Long =
            (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)

        locations.forEachIndexed { index, p ->
            val key = cellKey(Math.floor(p.east / eps).toInt(), Math.floor(p.north / eps).toInt())
            grid.getOrPut(key) { mutableListOf() }.add(index)
        }

        fun neighbors(i: Int): List<Int> {
            val p = locations[i]
            val cx = Math.floor(p.east / eps).toInt()
            val cy = Math.floor(p.north / eps).toInt()
            val result = mutableListOf<Int>()
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val bucket = grid[cellKey(cx + dx, cy + dy)] ?: continue
                    for (j in bucket) {
                        if (locations[i].distanceTo(locations[j]) <= eps) result.add(j)
                    }
                }
            }
            return result.sorted()
        }

        val visited = BooleanArray(points.size)
        val clustered = BooleanArray(points.size)
        val clusters = mutableListOf<List<DefectRecord>>()

        for (i in points.indices) {
            if (visited[i]) continue
            visited[i] = true
            val n = neighbors(i)
            if (n.size < minPts) continue

            val clusterIdx = mutableListOf(i)
            clustered[i] = true
            var cursor = 0
            while (cursor < clusterIdx.size) {
                val current = clusterIdx[cursor++]
                visited[current] = true
                val currentNeighbors = if (current == i) n else neighbors(current)
                if (currentNeighbors.size >= minPts) {
                    for (candidate in currentNeighbors) {
                        if (!clustered[candidate]) {
                            clustered[candidate] = true
                            clusterIdx.add(candidate)
                        }
                    }
                }
            }
            clusters.add(clusterIdx.map { points[it] })
        }
        return clusters
    }

    private fun toCluster(
        type: DefectType,
        members: List<DefectRecord>,
        frame: GeoMath.LocalFrame,
        index: Int,
    ): DefectCluster {
        val local = members.map { frame.toLocal(it.location!!) }
        val hull = GeoMath.convexHull(local)
        val meanRadius = members.map { sqrt(it.estimatedAreaM2 / Math.PI) }.average().takeIf { !it.isNaN() } ?: 0.0
        val isAreal = hull.size >= 3 && GeoMath.polygonAreaM2(hull) > 1e-6
        val polygonLocal = if (isAreal) {
            GeoMath.bufferPolygon(hull, meanRadius.coerceIn(0.0, 3.0))
        } else {
            GeoMath.envelopePolygon(local, meanRadius.coerceIn(0.25, 3.0))
        }

        val sumArea = members.sumOf { it.estimatedAreaM2 }
        // Площадь зоны не может быть меньше суммы площадей дефектов; для коллинеарных
        // трещин используем сумму (оболочка-коридор нужна только для карты).
        val area = if (isAreal) {
            maxOf(sumArea, GeoMath.polygonAreaM2(polygonLocal))
        } else {
            sumArea
        }.coerceAtLeast(0.01)
        val center = GeoMath.centroid(local)

        val depths = members.filter { it.confirmed }.map { it.estimatedDepthMm }.ifEmpty {
            members.map { it.estimatedDepthMm }
        }

        return DefectCluster(
            id = "CL-${type.name.take(2)}-$index-${members.first().id.takeLast(6)}",
            type = type,
            center = frame.toGeo(center),
            polygon = polygonLocal.map { frame.toGeo(it) },
            areaM2 = area,
            maxDepthMm = depths.maxOrNull() ?: 0.0,
            avgDepthMm = depths.average().takeIf { !it.isNaN() } ?: 0.0,
            maxSeverity = members.maxOf { it.severity },
            defectCount = members.size,
            status = if (members.any { it.confirmed }) {
                ConfirmationStatus.CONFIRMED_PHYSICAL
            } else {
                ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY
            },
        )
    }
}
