package ai.roadscan.core.report

import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectRecord
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.GeoPoint
import ai.roadscan.core.model.ReviewDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionDiffTest {

    private var nextId = 0

    private fun defect(
        lat: Double? = 50.0,
        lon: Double? = 30.0,
        type: DefectType = DefectType.POTHOLE,
        id: String = "d${nextId++}",
    ) = DefectRecord(
        id = id,
        sessionId = "s",
        timestampMs = 1L,
        location = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
        type = type,
        severity = 3,
        confirmed = true,
        status = ConfirmationStatus.CONFIRMED_PHYSICAL,
        estimatedAreaM2 = 0.5,
        estimatedDepthMm = 40.0,
        visionConfidence = 0.9f,
        impactG = 1.2f,
        nearField = true,
        latitude = lat,
        longitude = lon,
        review = ReviewDecision.UNREVIEWED,
    )

    /** ~0.00009° широты ≈ 10 м; 0.000045° ≈ 5 м. */
    private fun side(vararg defects: DefectRecord, area: Double = 1.0, cost: Double = 100.0) =
        SurveySide(defects.toList(), area, cost)

    @Test
    fun `ремонт устранил дефект — resolved без пары`() {
        // Новый дефект того же типа в 110 м — вне радиуса 5 м.
        val before = side(defect(lat = 50.0, lon = 30.0))
        val after = side(defect(id = "far", lat = 50.001, lon = 30.0))
        val delta = SessionDiff.of(before, after)
        assertEquals(1, delta.resolved)
        assertEquals(1, delta.appeared)
        assertEquals(0, delta.remaining)
        assertTrue(delta.matched)
    }

    @Test
    fun `оставшийся дефект сопоставляется в радиусе GPS`() {
        val old = defect(lat = 50.0, lon = 30.0)
        val fresh = defect(id = "f1", lat = 50.00003, lon = 30.0) // ≈ 3 м
        val delta = SessionDiff.of(side(old), side(fresh))
        assertEquals(1, delta.remaining)
        assertEquals(0, delta.resolved)
        assertEquals(0, delta.appeared)
        assertEquals(0.0, delta.changePercent!!, 0.01)
        assertEquals(SessionDiff.Trend.FLAT, SessionDiff.trend(delta))
    }

    @Test
    fun `разные типы в одном месте — не пара`() {
        val old = defect(type = DefectType.POTHOLE)
        val fresh = defect(id = "f1", type = DefectType.RUTTING) // рядом, но другой тип
        val delta = SessionDiff.of(side(old), side(fresh))
        assertEquals(1, delta.resolved)
        assertEquals(1, delta.appeared)
        assertEquals(0, delta.remaining)
    }

    @Test
    fun `без координат — только агрегаты, матчинг выключен`() {
        val before = side(defect(lat = null, lon = null))
        val after = side(defect(lat = null, lon = null))
        val delta = SessionDiff.of(before, after)
        assertFalse(delta.matched)
        assertNull(delta.resolved)
        assertNull(delta.remaining)
        assertNull(delta.appeared)
        assertEquals(0.0, delta.changePercent!!, 0.01)
    }

    @Test
    fun `агрегаты, структура по типам и тренд`() {
        val before = side(
            defect(type = DefectType.POTHOLE),
            defect(type = DefectType.POTHOLE),
            defect(type = DefectType.CRACK_NETWORK),
            area = 12.0,
            cost = 5_000.0,
        )
        val after = side(
            defect(type = DefectType.POTHOLE),
            area = 3.0,
            cost = 900.0,
        )
        val delta = SessionDiff.of(before, after)
        assertEquals(3, delta.defectsBefore)
        assertEquals(1, delta.defectsAfter)
        assertEquals(12.0, delta.areaBeforeM2)
        assertEquals(900.0, delta.costAfter)
        assertEquals(4_100.0, delta.costSaved)
        // (1-3)/3 ≈ -67% — дорога «похорошела».
        assertEquals(-66.67, delta.changePercent!!, 0.01)
        assertEquals(SessionDiff.Trend.BETTER, SessionDiff.trend(delta))
        val crack = delta.byType.first { it.type == DefectType.CRACK_NETWORK }
        assertEquals(1, crack.before)
        assertEquals(0, crack.after)
        val holes = delta.byType.first { it.type == DefectType.POTHOLE }
        assertEquals(2, holes.before)
        assertEquals(1, holes.after)
    }

    @Test
    fun `пустое прошлое — динамики нет`() {
        val delta = SessionDiff.of(side(), side(defect()))
        assertNull(delta.changePercent)
        assertEquals(SessionDiff.Trend.UNKNOWN, SessionDiff.trend(delta))
        // Обе стороны пустые → матчинг «всё гео» не применяется (нет данных).
        assertFalse(SessionDiff.of(side(), side()).matched)
        assertNotNull(SessionDiff.of(side(defect()), side(defect())).matched)
    }
}
