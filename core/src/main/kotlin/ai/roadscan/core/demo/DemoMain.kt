package ai.roadscan.core.demo

import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.agent.PostProcessingAgent
import ai.roadscan.core.agent.RoadScanAgent
import ai.roadscan.core.geo.GeoMath
import ai.roadscan.core.model.AgentEvent
import ai.roadscan.core.model.BoundingBox
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.EnvironmentSnapshot
import ai.roadscan.core.model.FrameData
import ai.roadscan.core.model.GeoPoint
import ai.roadscan.core.model.SensorPeak
import ai.roadscan.core.model.VisionDetection
import ai.roadscan.core.report.GeoJsonExporter
import ai.roadscan.core.report.ReportXlsxBuilder
import java.io.File

/**
 * Синтетическая смена: 300 м дороги, 60 дефектов, у 70% — удар подвески.
 * Запуск: ./gradlew :core:demo
 */
object DemoMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.firstOrNull() ?: "build/demo").apply { mkdirs() }
        val origin = GeoPoint(55.7558, 37.6173, 3f)
        val config = AgentConfig.DEFAULT

        val events = mutableListOf<String>()
        val agent = RoadScanAgent(
            config = config,
            eventListener = { event ->
                when (event) {
                    is AgentEvent.VoicePrompt -> events += event.message
                    is AgentEvent.StateChanged -> events += "${event.from} -> ${event.to}"
                    else -> Unit
                }
            },
        )

        agent.startSession(
            EnvironmentSnapshot(
                gpsAccuracyM = 3.5f,
                freeDiskMb = 12_000,
                batteryPercent = 78,
                batteryTempC = 31f,
                cpuTempC = 36f,
            ),
        )

        val start = System.currentTimeMillis() - 32 * 60 * 1000
        var confirmed = 0
        var visual = 0

        for (i in 0 until 200) {
            val north = i * 1.6
            val location = GeoMath.offset(origin, northM = north, eastM = 0.3)
            val isPothole = i % 3 != 0
            val type = if (isPothole) DefectType.POTHOLE else DefectType.CRACK_NETWORK
            val detection = VisionDetection(
                type = type,
                confidence = 0.72f + (i % 5) * 0.05f,
                box = BoundingBox(0.34f, 0.70f, 0.52f, 0.77f),
            )
            val frame = FrameData(
                timestampMs = start + i * 1000L,
                location = location,
                cameraHeightM = 1.3f,
                cameraPitchDeg = 8f,
                imageWidth = 1280,
                imageHeight = 720,
                speedKmh = 45f,
                meanContrast = 0.42f,
                sensorTimestampMs = i * 1000L,
                imageRef = null,
                detections = listOf(detection),
            )
            val peak = if (i % 10 != 0) SensorPeak(i * 1000L, 0.55f + (i % 4) * 0.15f, 35) else null
            val record = agent.evaluateFrameAndSensor(frame, peak)
            if (record != null) {
                if (record.confirmed) confirmed++ else visual++
            }
            // лёгкий нагрев к середине смены, затем остывание
            if (i % 20 == 0) {
                val temp = if (i in 80..120) 44f else 37f
                agent.onVehicleTelemetry(
                    ai.roadscan.core.model.VehicleTelemetry(
                        timestampMs = i * 1000L,
                        speedKmh = 45f,
                        cpuTempC = temp,
                        batteryPercent = 78 - i / 20,
                        batteryTempC = 33f,
                        freeDiskMb = 12_000,
                    ),
                )
            }
        }

        val data = agent.finishSession() ?: error("session not finished")
        val report = PostProcessingAgent(config).process(data)

        val xlsx = File(outDir, "${report.sessionId}.xlsx").apply { writeBytes(ReportXlsxBuilder.build(report)) }
        val geojson = File(outDir, "${report.sessionId}.geojson").apply {
            writeText(GeoJsonExporter.export(report))
        }

        println("=== RoadScan AI demo ===")
        println("Дефектов подтверждено: $confirmed, visual-only: $visual")
        println("Кластеров: ${report.clusters.size} (+${report.visualOnlyClusters.size} visual-only)")
        println("Площадь: %.2f м²".format(report.estimate.totalAreaM2))
        println("Фрезеровка: %.3f м³".format(report.estimate.totalMillingVolumeM3))
        println("Асфальтобетон: %.3f т".format(report.estimate.totalAsphaltTons))
        println("Смета: %.0f ₽".format(report.estimate.totalRub))
        println("Средняя задержка кадра: %.2f мс".format(report.stats.averageLatencyMs))
        println("XLSX: ${xlsx.absolutePath}")
        println("GeoJSON: ${geojson.absolutePath}")
        println("События (первые 5): ${events.take(5).joinToString(" | ")}")
    }
}
