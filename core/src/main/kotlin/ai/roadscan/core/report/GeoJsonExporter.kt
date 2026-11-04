package ai.roadscan.core.report

import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.i18n.DocStrings
import ai.roadscan.core.model.ConfirmationStatus
import java.util.Locale

/**
 * GeoJSON (RFC 7946) для загрузки в ГИС: подтверждённые кластеры — полигоны,
 * visual-only — точки/полигоны с флагом, чтобы аналитик видел кандидатов.
 */
object GeoJsonExporter {

    fun export(
        report: SessionReport,
        includeVisualOnly: Boolean = true,
        strings: DocStrings = DocStrings.DEFAULT,
    ): String {
        val features = mutableListOf<String>()

        for (cluster in report.clusters) {
            features += feature(
                geometry = geometryFor(cluster),
                properties = mapOf(
                    "cluster_id" to cluster.id,
                    "session_id" to report.sessionId,
                    "type" to cluster.type.name,
                    "type_name" to strings.defectType(cluster.type),
                    "status" to cluster.status.name,
                    "status_label" to strings.confirmation(cluster.status),
                    "area_m2" to round(cluster.areaM2, 2),
                    "max_depth_mm" to round(cluster.maxDepthMm, 1),
                    "avg_depth_mm" to round(cluster.avgDepthMm, 1),
                    "max_severity" to cluster.maxSeverity,
                    "defects" to cluster.defectCount,
                ),
            )
        }

        if (includeVisualOnly) {
            for (cluster in report.visualOnlyClusters) {
                features += feature(
                    geometry = geometryFor(cluster),
                    properties = mapOf(
                        "cluster_id" to cluster.id,
                        "session_id" to report.sessionId,
                        "type" to cluster.type.name,
                        "type_name" to strings.defectType(cluster.type),
                        "status" to ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY.name,
                        "status_label" to strings.confirmation(cluster.status),
                        "area_m2" to round(cluster.areaM2, 2),
                        "max_depth_mm" to round(cluster.maxDepthMm, 1),
                        "avg_depth_mm" to round(cluster.avgDepthMm, 1),
                        "max_severity" to cluster.maxSeverity,
                        "defects" to cluster.defectCount,
                    ),
                )
            }
        }

        return buildString {
            append("{\"type\":\"FeatureCollection\",\"properties\":{")
            append("\"session_id\":\"${escape(report.sessionId)}\",")
            append("\"started_at_ms\":${report.startedAtMs},")
            append("\"ended_at_ms\":${report.endedAtMs},")
            append("\"generator\":\"RoadScan AI Agent\",")
            append("\"locale\":\"${strings.locale}\",")
            append("\"currency\":\"${escape(report.estimate.currency)}\",")
            append("\"object_name\":\"${escape(report.meta.objectName)}\",")
            append("\"road_name\":\"${escape(report.meta.roadName)}\",")
            append("\"customer\":\"${escape(report.meta.customer)}\",")
            append("\"contractor\":\"${escape(report.meta.contractor)}\",")
            append("\"demo\":${report.meta.demo}")
            append("},\"features\":[")
            append(features.joinToString(","))
            append("]}")
        }
    }

    private fun feature(geometry: String, properties: Map<String, Any>): String {
        val props = properties.entries.joinToString(",") { (k, v) ->
            val value = when (v) {
                is String -> "\"${escape(v)}\""
                is Number -> v.toString()
                else -> "\"${escape(v.toString())}\""
            }
            "\"${escape(k)}\":$value"
        }
        return "{\"type\":\"Feature\",\"geometry\":$geometry,\"properties\":{$props}}"
    }

    /**
     * Геометрия зоны повреждения.
     *
     * Все объекты выгружаются полигонами, а не точками: в ГИС зона нужна для
     * обмеження ділянки та площі, точка її втрачає (мережа тріщин на 10 м²
     * виглядала б як крапка). Статус при цьому зберігається у властивостях
     * об'єкта, тому аналітик відрізняє підтверджені дефекти від кандидатів.
     */
    private fun geometryFor(cluster: ai.roadscan.core.geo.DefectCluster): String {
        if (cluster.polygon.size >= 3) return polygon(cluster.polygon)
        return squareAround(cluster.center, cluster.areaM2)
    }

    /** Квадрат по центру и площади — запасной вариант, если полигона нет. */
    private fun squareAround(center: ai.roadscan.core.model.GeoPoint, areaM2: Double): String {
        val half = kotlin.math.sqrt(kotlin.math.max(areaM2, 0.04) / 4.0)
        val dLat = half / 111_320.0
        val dLon = half / (111_320.0 * kotlin.math.cos(Math.toRadians(center.lat)))
        return polygon(
            listOf(
                ai.roadscan.core.model.GeoPoint(center.lat - dLat, center.lon - dLon),
                ai.roadscan.core.model.GeoPoint(center.lat - dLat, center.lon + dLon),
                ai.roadscan.core.model.GeoPoint(center.lat + dLat, center.lon + dLon),
                ai.roadscan.core.model.GeoPoint(center.lat + dLat, center.lon - dLon),
            ),
        )
    }

    private fun polygon(points: List<ai.roadscan.core.model.GeoPoint>): String {
        val coords = points.joinToString(",") {
            String.format(Locale.US, "[%.7f,%.7f]", it.lon, it.lat)
        }
        val closed = if (points.isNotEmpty() && points.first() != points.last()) {
            coords + String.format(Locale.US, ",[%.7f,%.7f]", points.first().lon, points.first().lat)
        } else {
            coords
        }
        return "{\"type\":\"Polygon\",\"coordinates\":[[$closed]]}"
    }

    private fun round(value: Double, digits: Int): Double {
        val factor = Math.pow(10.0, digits.toDouble())
        return Math.round(value * factor) / factor
    }

    private fun escape(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
}
