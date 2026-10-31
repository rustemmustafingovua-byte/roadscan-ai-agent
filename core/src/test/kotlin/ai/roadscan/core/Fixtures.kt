package ai.roadscan.core

import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectRecord
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.FrameData
import ai.roadscan.core.model.GeoPoint
import ai.roadscan.core.model.VisionDetection
import java.util.UUID

object Fixtures {
    val ORIGIN = GeoPoint(55.7558, 37.6173, 4f)

    fun detection(
        type: DefectType = DefectType.POTHOLE,
        confidence: Float = 0.9f,
        left: Float = 0.40f,
        top: Float = 0.70f,
        right: Float = 0.52f,
        bottom: Float = 0.82f,
    ): VisionDetection = VisionDetection(
        type = type,
        confidence = confidence,
        box = BoundingBox(left, top, right, bottom),
    )

    fun frame(
        timestampMs: Long = 1_000_000L,
        location: GeoPoint? = ORIGIN,
        detection: VisionDetection? = detection(),
        contrast: Float = 0.45f,
        speedKmh: Float = 40f,
        imageWidth: Int = 1080,
        imageHeight: Int = 1920,
    ): FrameData = FrameData(
        timestampMs = timestampMs,
        location = location,
        cameraHeightM = 1.3f,
        cameraPitchDeg = 8f,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        speedKmh = speedKmh,
        meanContrast = contrast,
        detections = listOfNotNull(detection),
    )

    fun defect(
        location: GeoPoint = ORIGIN,
        type: DefectType = DefectType.POTHOLE,
        areaM2: Double = 0.8,
        depthMm: Double = 45.0,
        severity: Int = 4,
        confirmed: Boolean = true,
        timestampMs: Long = 1_000_000L,
    ): DefectRecord = DefectRecord(
        id = "DEF-" + UUID.randomUUID().toString().take(8),
        sessionId = "SES-test",
        timestampMs = timestampMs,
        location = location,
        type = type,
        severity = severity,
        confirmed = confirmed,
        status = if (confirmed) {
            ConfirmationStatus.CONFIRMED_PHYSICAL
        } else {
            ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY
        },
        estimatedAreaM2 = areaM2,
        estimatedDepthMm = depthMm,
        visionConfidence = 0.9f,
        impactG = if (confirmed) 0.7f else 0f,
        nearField = true,
    )
}
