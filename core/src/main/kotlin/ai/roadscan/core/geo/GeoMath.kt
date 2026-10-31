package ai.roadscan.core.geo

import ai.roadscan.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Геодезические примитивы без зависимостей от Android. */
object GeoMath {
    const val EARTH_RADIUS_M: Double = 6_371_008.8

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Смещение точки на north/east метров (локальная плоскость). */
    fun offset(origin: GeoPoint, northM: Double, eastM: Double): GeoPoint {
        val dLat = northM / EARTH_RADIUS_M
        val dLon = eastM / (EARTH_RADIUS_M * cos(Math.toRadians(origin.lat)))
        return GeoPoint(
            lat = origin.lat + Math.toDegrees(dLat),
            lon = origin.lon + Math.toDegrees(dLon),
        )
    }

    data class LocalPoint(val east: Double, val north: Double) {
        fun distanceTo(other: LocalPoint): Double =
            sqrt((east - other.east) * (east - other.east) + (north - other.north) * (north - other.north))
    }

    /** Локальная ENU-плоскость относительно опорной точки. */
    class LocalFrame(val origin: GeoPoint) {
        fun toLocal(point: GeoPoint): LocalPoint {
            val north = Math.toRadians(point.lat - origin.lat) * EARTH_RADIUS_M
            val east = Math.toRadians(point.lon - origin.lon) * EARTH_RADIUS_M *
                cos(Math.toRadians(origin.lat))
            return LocalPoint(east, north)
        }

        fun toGeo(local: LocalPoint): GeoPoint = offset(origin, local.north, local.east)
    }

    /** Площадь полигона (shoelace) в м²; вершины в локальных метрах. */
    fun polygonAreaM2(points: List<LocalPoint>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val p = points[i]
            val q = points[(i + 1) % points.size]
            sum += p.east * q.north - q.east * p.north
        }
        return abs(sum) / 2.0
    }

    fun centroid(points: List<LocalPoint>): LocalPoint {
        if (points.isEmpty()) return LocalPoint(0.0, 0.0)
        return LocalPoint(points.sumOf { it.east } / points.size, points.sumOf { it.north } / points.size)
    }

    /** Выпуклая оболочка (monotone chain), против часовой стрелки. */
    fun convexHull(points: List<LocalPoint>): List<LocalPoint> {
        if (points.size <= 2) return points
        val sorted = points.sortedWith(compareBy({ it.east }, { it.north }))
        val cross = { o: LocalPoint, a: LocalPoint, b: LocalPoint ->
            (a.east - o.east) * (b.north - o.north) - (a.north - o.north) * (b.east - o.east)
        }

        val lower = mutableListOf<LocalPoint>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = mutableListOf<LocalPoint>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** Прямоугольная оболочка (для коллинеарных точек/одиночных дефектов). */
    fun envelopePolygon(points: List<LocalPoint>, bufferM: Double): List<LocalPoint> {
        if (points.isEmpty()) return emptyList()
        var minE = points.minOf { it.east } - bufferM
        var maxE = points.maxOf { it.east } + bufferM
        var minN = points.minOf { it.north } - bufferM
        var maxN = points.maxOf { it.north } + bufferM
        // не даём оболочке выродиться в линию
        if (maxE - minE < 0.4) {
            val c = (minE + maxE) / 2
            minE = c - 0.2
            maxE = c + 0.2
        }
        if (maxN - minN < 0.4) {
            val c = (minN + maxN) / 2
            minN = c - 0.2
            maxN = c + 0.2
        }
        return listOf(
            LocalPoint(minE, minN),
            LocalPoint(maxE, minN),
            LocalPoint(maxE, maxN),
            LocalPoint(minE, maxN),
        )
    }

    /** Расширяет полигон от центроида на [bufferM] метров (приближение буфера). */
    fun bufferPolygon(points: List<LocalPoint>, bufferM: Double): List<LocalPoint> {
        val c = centroid(points)
        return points.map { p ->
            val dx = p.east - c.east
            val dy = p.north - c.north
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-6) p else LocalPoint(
                east = p.east + dx / len * bufferM,
                north = p.north + dy / len * bufferM,
            )
        }
    }
}
