package ai.roadscan.app.data

import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectRecord
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.GeoPoint
import ai.roadscan.core.model.ReviewDecision

/** Офлайн-хранилище смен и дефектов (Room). */
class RoadScanRepository(private val dao: RoadScanDao) {

    fun observeSessions() = dao.observeSessions()

    fun observeSessionCount() = dao.observeSessionCount()

    /**
     * Смена заводится в базе сразу после старта, а дефекты дописываются по
     * мере находки. Если Android убьёт процесс (мало памяти, пользователь
     * смахнул приложение), собранное не пропадает: сессия остаётся в списке
     * и открывается как есть. `endedAtMs = 0` — признак незавершённой смены.
     */
    suspend fun beginSession(
        sessionId: String,
        startedAtMs: Long,
        metaJson: String = "",
        calibrationJson: String = "",
        createdAtMs: Long = System.currentTimeMillis(),
    ) {
        dao.insertSession(
            SessionEntity(
                id = sessionId,
                startedAtMs = startedAtMs,
                endedAtMs = 0L,
                confirmedCount = 0,
                visualOnlyCount = 0,
                totalAreaM2 = 0.0,
                total = 0.0,
                createdAtMs = createdAtMs,
                metaJson = metaJson,
                calibrationJson = calibrationJson,
            ),
        )
    }

    /** Дописывает новые дефекты незавершённой смены (идемпотентно по id). */
    suspend fun appendDefects(defects: List<DefectRecord>) {
        if (defects.isEmpty()) return
        dao.insertDefects(defects.map { it.toEntity() })
    }

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

    /**
     * Сколько дефектов каждого типа по завершённым сменам архива.
     * Неизвестные имена (миграции) молча пропускаются.
     */
    suspend fun archiveTypeCounts(
        fromMs: Long = 0L,
        toMs: Long = Long.MAX_VALUE,
    ): List<Pair<ai.roadscan.core.model.DefectType, Int>> =
        dao.archiveTypeCounts(fromMs, toMs).mapNotNull { row ->
            runCatching { ai.roadscan.core.model.DefectType.valueOf(row.type) }
                .getOrNull()
                ?.let { it to row.cnt }
        }

    /**
     * Удаляет смену: дефекты, строка смены и снимки находок на диске
     * (фото — главный потребитель места в архиве).
     */
    suspend fun deleteSession(sessionId: String) {
        val photos = dao.defectsFor(sessionId).mapNotNull { it.photoPath }
        dao.deleteDefectsOf(sessionId)
        dao.deleteSession(sessionId)
        photos.forEach { path -> runCatching { java.io.File(path).delete() } }
    }

    /** Реквизиты и калибровка сохранённой смены. */
    fun metaOf(session: SessionEntity): Pair<ai.roadscan.core.report.ReportMeta, ai.roadscan.core.calibration.CalibrationProfile?> =
        ai.roadscan.core.report.ReportMeta.fromJson(session.metaJson) to
            session.calibrationJson.takeIf { it.isNotBlank() }
                ?.let { ai.roadscan.core.calibration.CalibrationProfile.fromJson(it) }

    suspend fun loadDefects(sessionId: String): List<DefectRecord> =
        dao.defectsFor(sessionId).map { it.toRecord() }

    /**
     * Сохраняет решение оператора по находке.
     *
     * Отклонённые дефекты не попадают в ведомость и смету, а подтверждённые
     * оператором — попадают, даже если автоматика удар не увидела.
     */
    suspend fun setReview(defectId: String, decision: ReviewDecision) {
        dao.setReview(defectId, decision.ordinal)
    }

    /** Привязывает снимок находки к записи в базе. */
    suspend fun setPhoto(defectId: String, path: String) {
        dao.setPhoto(defectId, path)
    }

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
        km = km,
        review = review.ordinal,
        photoPath = photoPath,
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
            km = km,
            review = ReviewDecision.entries.getOrElse(review) { ReviewDecision.UNREVIEWED },
            photoPath = photoPath,
        )
    }
}
