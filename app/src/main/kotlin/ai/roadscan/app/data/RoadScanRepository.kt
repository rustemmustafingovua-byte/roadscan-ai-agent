package ai.roadscan.app.data

import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectRecord
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.GeoPoint

/** Офлайн-хранилище смен и дефектов (Room). */
class RoadScanRepository(private val dao: RoadScanDao) {

    fun observeSessions() = dao.observeSessions()

    fun observeSessionCount() = dao.observeSessionCount()

    suspend fun save(report: SessionReport, createdAtMs: Long = System.currentTimeMillis()) {
        val session = SessionEntity(
            id = report.sessionId,
            startedAtMs = report.startedAtMs,
            endedAtMs = report.endedAtMs,
            confirmedCount = report.stats.confirmed,
            visualOnlyCount = report.stats.visualOnly,
            totalAreaM2 = report.estimate.totalAreaM2,
            total = report.estimate.total,
            createdAtMs = createdAtMs,
            metaJson = ai.roadscan.core.report.ReportMeta.toJson(report.meta),
            calibrationJson = report.calibration?.let { ai.roadscan.core.calibration.CalibrationProfile.toJson(it) }
                ?: "",
        )
        dao.saveSession(session, report.defects.map { it.toEntity() })
    }

    /** Реквизиты и калибровка сохранённой смены. */
    fun metaOf(session: SessionEntity): Pair<ai.roadscan.core.report.ReportMeta, ai.roadscan.core.calibration.CalibrationProfile?> =
        ai.roadscan.core.report.ReportMeta.fromJson(session.metaJson) to
            session.calibrationJson.takeIf { it.isNotBlank() }
                ?.let { ai.roadscan.core.calibration.CalibrationProfile.fromJson(it) }

    suspend fun loadDefects(sessionId: String): List<DefectRecord> =
        dao.defectsFor(sessionId).map { it.toRecord() }

    private fun DefectRecord.toEntity() = DefectEntity(
        id = id,
        sessionId = sessionId,
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        type = type.name,
        severity = severity,
        confirmed = confirmed,
        status = status.name,
        areaM2 = estimatedAreaM2,
        depthMm = estimatedDepthMm,
        visionConfidence = visionConfidence,
        impactG = impactG,
        nearField = nearField,
    )

    private fun DefectEntity.toRecord(): DefectRecord {
        val location = if (latitude != null && longitude != null) {
            GeoPoint(latitude, longitude)
        } else {
            null
        }
        return DefectRecord(
            id = id,
            sessionId = sessionId,
            timestampMs = timestampMs,
            location = location,
            type = runCatching { DefectType.valueOf(type) }.getOrDefault(DefectType.UNKNOWN),
            severity = severity,
            confirmed = confirmed,
            status = runCatching { ConfirmationStatus.valueOf(status) }
                .getOrDefault(ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY),
            estimatedAreaM2 = areaM2,
            estimatedDepthMm = depthMm,
            visionConfidence = visionConfidence,
            impactG = impactG,
            nearField = nearField,
            latitude = latitude,
            longitude = longitude,
        )
    }
}
