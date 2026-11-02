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
            val geometry = if (cluster.polygon.size >= 3) {
                polygon(cluster.polygon)
            } else {
                point(cluster.center.lat, cluster.center.lon)
            }
            features += feature(
                geometry = geometry,
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
                    geometry = point(cluster.center.lat, cluster.center.lon),
                    properties = mapOf(
                        "cluster_id" to cluster.id,
                        "session_id" to report.sessionId,
                        "type" to cluster.type.name,
                        "type_name" to strings.defectType(cluster.type),
                        "status" to ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY.name,
                        "status_label" to strings.confirmation(cluster.status),
                        "area_m2" to round(cluster.areaM2, 2),
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

    private fun point(lat: Double, lon: Double): String =
        String.format(Locale.US, "{\"type\":\"Point\",\"coordinates\":[%.7f,%.7f]}", lon, lat)

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
