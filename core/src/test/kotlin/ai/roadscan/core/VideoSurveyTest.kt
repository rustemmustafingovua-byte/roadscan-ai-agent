package ai.roadscan.core

import ai.roadscan.core.calibration.CalibrationProfile
import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.video.ObservedDefect
import ai.roadscan.core.video.RoadSection
import ai.roadscan.core.video.SurveyConfig
import ai.roadscan.core.video.VideoAnalysis
import ai.roadscan.core.video.VideoSurveyPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoSurveyTest {

    private fun observed(
        timeMs: Long,
        type: DefectType = DefectType.POTHOLE,
        confidence: Float = 0.8f,
        left: Float = 0.30f,
        top: Float = 0.55f,
        right: Float = 0.55f,
        bottom: Float = 0.80f,
    ) = ObservedDefect(
        timeMs = timeMs,
        type = type,
        confidence = confidence,
        box = BoundingBox(left, top, right, bottom),
    )

    private fun analysis(
        vararg detections: ObservedDefect,
        photos: Map<Long, String> = emptyMap(),
    ) = VideoAnalysis(
        durationMs = 10_000,
        width = 1280,
        height = 720,
        sampledFps = 3f,
        detections = detections.toList(),
        photos = photos,
    )

    @Test
    fun `same defect in many frames becomes one confirmed record`() {
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(
                observed(1_000),
                observed(1_333),
                observed(1_666),
                observed(2_000),
            ),
            section = RoadSection(kmFrom = 10.0, kmTo = 20.0),
        )

        assertEquals(1, result.size)
        val defect = result.single()
        assertEquals(4, defect.frames)
        assertTrue(defect.confirmed, "Устойчивое присутствие в кадрах — подтверждение")
        assertEquals(DefectType.POTHOLE, defect.type)
        assertTrue(defect.areaM2 > 0.0, "Площадь должна оцениваться по рамке")
    }

    @Test
    fun `single frame detection stays visual only`() {
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(observed(1_000)),
            section = RoadSection(kmFrom = 0.0, kmTo = 10.0),
            config = SurveyConfig(minPersistentFrames = 3),
        )

        val defect = result.single()
        assertFalse(defect.confirmed, "Одиночный кадр — не подтверждение")
        assertEquals(0.0, defect.depthMm, 1e-9)
    }

    @Test
    fun `defects at different places are not merged`() {
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(
                observed(1_000, left = 0.10f, right = 0.30f),
                observed(1_333, left = 0.10f, right = 0.30f),
                observed(1_666, left = 0.70f, right = 0.90f),
                observed(2_000, left = 0.70f, right = 0.90f),
            ),
            section = RoadSection(kmFrom = 0.0, kmTo = 10.0),
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `defects of different types are not merged`() {
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(
                observed(1_000, type = DefectType.POTHOLE),
                observed(1_333, type = DefectType.CRACK_NETWORK),
                observed(1_666, type = DefectType.CRACK_NETWORK),
            ),
            section = RoadSection(kmFrom = 0.0, kmTo = 10.0),
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `kilometer is interpolated along the section`() {
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(
                observed(1_000, confidence = 0.6f),
                observed(1_333, confidence = 0.7f),
                observed(1_666, confidence = 0.8f),
            ),
            section = RoadSection(kmFrom = 10.0, kmTo = 20.0),
        )

        // Пик — кадр с максимальной уверенностью (1.666 с из 10 с)
        // -> 10 + 10 * 0.1666 = 11.67 км
        val defect = result.single()
        assertTrue(defect.km in 11.5..11.8, "Км должен считаться по времени: ${defect.km}")
        assertEquals(0.8f, defect.confidence, 1e-4f)
    }

    @Test
    fun `coordinates are placed along the bearing when section has geometry`() {
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(
                observed(5_000),
                observed(5_333),
                observed(5_666),
            ),
            section = RoadSection(
                kmFrom = 0.0,
                kmTo = 10.0,
                startLat = 50.45,
                startLon = 30.52,
                bearingDeg = 90.0, // на восток
            ),
        )

        val defect = result.single()
        assertNotNull(defect.lat)
        assertNotNull(defect.lon)
        // Половина участка на восток: долгота растёт, широта почти не меняется.
        assertTrue(defect.lon > 30.52, "Долгота должна расти на восток")
        assertTrue(defect.lat in 50.44..50.46, "Широта почти не меняется: ${defect.lat}")
    }

    @Test
    fun `without section geometry the defect has no coordinates`() {
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(observed(1_000), observed(1_333), observed(1_666)),
            section = RoadSection(kmFrom = 5.0, kmTo = 6.0),
        )

        val defect = result.single()
        assertNull(defect.lat)
        assertNull(defect.lon)
        assertTrue(defect.km in 5.0..6.0)
    }

    @Test
    fun `defect carries the photo closest to its peak frame`() {
        // Снимки снимаются по ходу анализа, поэтому к пику берём ближайший:
        // именно на нём дефект виден лучше всего.
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(
                observed(1_000, confidence = 0.5f),
                observed(1_333, confidence = 0.7f),
                observed(1_666, confidence = 0.9f),
                photos = mapOf(
                    1_000L to "/photos/t1000.jpg",
                    1_666L to "/photos/t1666.jpg",
                ),
            ),
            section = RoadSection(kmFrom = 10.0, kmTo = 20.0),
        )

        val defect = result.single()
        // Пик уверенности — кадр 1666 мс, снимок ровно с него.
        assertEquals("/photos/t1666.jpg", defect.photoPath)
        val record = VideoSurveyPlanner.toRecords(listOf(defect), "SUR-TEST").single()
        assertEquals("/photos/t1666.jpg", record.photoPath)
    }

    @Test
    fun `defect without photos stays without photo`() {
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(observed(1_000), observed(1_333), observed(1_666)),
            section = RoadSection(kmFrom = 0.0, kmTo = 1.0),
        )
        assertNull(result.single().photoPath)
    }

    @Test
    fun `records carry the session id given by the caller`() {
        // Регресс: смена и записи дефектов разошлись по id, и ведомость
        // открывалась пустой.
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(observed(1_000), observed(1_333), observed(1_666)),
            section = RoadSection(kmFrom = 1.0, kmTo = 2.0),
        )
        val records = VideoSurveyPlanner.toRecords(result, "SUR-TEST")
        assertEquals(1, records.size)
        assertEquals("SUR-TEST", records.single().sessionId)
        assertTrue(result.all { it.toRecord("SUR-TEST").sessionId == "SUR-TEST" })
    }

    @Test
    fun `record conversion keeps confirmation status`() {
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(observed(1_000), observed(1_333), observed(1_666)),
            section = RoadSection(kmFrom = 0.0, kmTo = 10.0),
        )

        val record = result.single().toRecord("SES-test")
        assertTrue(record.confirmed)
        assertEquals(
            ai.roadscan.core.model.ConfirmationStatus.CONFIRMED_PHYSICAL,
            record.status,
        )
        assertEquals("SES-test", record.sessionId)
    }

    @Test
    fun `calibration affects estimated area`() {
        val detections = arrayOf(observed(1_000), observed(1_333), observed(1_666))
        val low = VideoSurveyPlanner.plan(
            analysis = analysis(*detections),
            calibration = CalibrationProfile(cameraHeightM = 1.3f, cameraPitchDeg = 8f),
        ).single().areaM2
        val high = VideoSurveyPlanner.plan(
            analysis = analysis(*detections),
            calibration = CalibrationProfile(cameraHeightM = 2.5f, cameraPitchDeg = 8f),
        ).single().areaM2

        assertTrue(high > low, "Более высокая камера видит больше площади: $high vs $low")
    }

    @Test
    fun `summary counts confirmed and visual only`() {
        val result = VideoSurveyPlanner.plan(
            analysis = analysis(
                observed(1_000),
                observed(1_333),
                observed(1_666),
                observed(5_000),
            ),
            section = RoadSection(kmFrom = 0.0, kmTo = 10.0),
        )

        val summary = VideoSurveyPlanner.summarize(result)
        assertEquals(2, summary.total)
        assertEquals(1, summary.confirmed)
        assertEquals(1, summary.visualOnly)
        assertTrue(summary.areaM2 > 0.0)
    }
}
