package ai.roadscan.core

import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.agent.Clock
import ai.roadscan.core.agent.PostProcessingAgent
import ai.roadscan.core.agent.RoadScanAgent
import ai.roadscan.core.i18n.CoreMessages
import ai.roadscan.core.model.AgentEvent
import ai.roadscan.core.model.AgentState
import ai.roadscan.core.model.EnvironmentSnapshot
import ai.roadscan.core.model.SensorPeak
import ai.roadscan.core.model.SensorSample
import ai.roadscan.core.model.VehicleTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadScanAgentTest {

    private val events = mutableListOf<AgentEvent>()

    private fun agent(clockMs: Long = 1_000_000L): RoadScanAgent =
        RoadScanAgent(
            config = AgentConfig.DEFAULT,
            clock = Clock { clockMs },
            eventListener = { events += it },
        )

    private fun env(
        gps: Float = 4f,
        diskMb: Long = 5_000,
        battery: Int = 80,
        batteryTemp: Float = 30f,
    ) = EnvironmentSnapshot(gps, diskMb, battery, batteryTemp)

    @Test
    fun `start requires free disk`() {
        val agent = agent()
        val result = agent.startSession(env(diskMb = 100))

        assertFalse(result.started)
        assertEquals(AgentState.ERROR, agent.state)
        assertTrue(events.any { it is AgentEvent.Error })
    }

    @Test
    fun `poor gps keeps agent calibrating and warns about rtk`() {
        val agent = agent()
        val result = agent.startSession(env(gps = 30f))

        assertTrue(result.started)
        assertEquals(AgentState.CALIBRATING, agent.state)
        val prompt = events.filterIsInstance<AgentEvent.VoicePrompt>().first().message
        assertEquals(CoreMessages.DEFAULT.gpsWarning, prompt)
    }

    @Test
    fun `calibration completes when gps becomes accurate`() {
        val agent = agent()
        agent.startSession(env(gps = 30f))
        agent.updateEnvironment(env(gps = 5f))
        assertEquals(AgentState.SCANNING, agent.state)
    }

    @Test
    fun `frame with pothole and impact becomes confirmed`() {
        val agent = agent()
        agent.startSession(env())
        val record = agent.evaluateFrameAndSensor(
            Fixtures.frame(),
            SensorPeak(timestampMs = 1_000_000L, deltaG = 0.9f, durationMs = 40),
        )

        assertNotNull(record)
        assertTrue(record.confirmed)
        assertEquals(ai.roadscan.core.model.ConfirmationStatus.CONFIRMED_PHYSICAL, record.status)
        assertTrue(record.estimatedDepthMm > 20)
        assertTrue(record.estimatedAreaM2 > 0)
    }

    @Test
    fun `pothole without impact is visual only`() {
        val agent = agent()
        agent.startSession(env())
        val record = agent.evaluateFrameAndSensor(Fixtures.frame(), sensorPeak = null)

        assertNotNull(record)
        assertFalse(record.confirmed)
        assertEquals(ai.roadscan.core.model.ConfirmationStatus.UNCONFIRMED_VISUAL_ONLY, record.status)
    }

    @Test
    fun `impact far from wheel is not confirmed`() {
        val agent = agent()
        agent.startSession(env())
        val farFrame = Fixtures.frame(detection = Fixtures.detection(top = 0.10f, bottom = 0.20f))
        val record = agent.evaluateFrameAndSensor(
            farFrame,
            SensorPeak(1_000_000L, deltaG = 1.2f),
        )

        assertNotNull(record)
        assertFalse(record.confirmed)
    }

    @Test
    fun `impact from accelerometer buffer confirms frame`() {
        val agent = agent()
        agent.startSession(env())
        // 200 Гц: 5 мс между семплами; удар за 20 мс до кадра.
        for (t in 1_000_000L..1_000_060L step 5) {
            agent.onSensorSample(SensorSample(t, if (t == 1_000_080L) 1.9f else 1.0f))
        }
        agent.onSensorSamples(
            (1_000_000L..1_000_060L step 5).map { SensorSample(it, 1.0f) } +
                SensorSample(1_000_080L, 1.9f),
        )

        val record = agent.evaluateFrameAndSensor(
            Fixtures.frame(timestampMs = 1_000_100L),
        )
        assertNotNull(record)
        assertTrue(record.confirmed, "expect confirmed, impact=${record.impactG}")
    }

    @Test
    fun `duplicate detection within four metres is merged`() {
        val agent = agent()
        agent.startSession(env())
        val first = agent.evaluateFrameAndSensor(Fixtures.frame(timestampMs = 1_000_000L), SensorPeak(1_000_000L, 0.9f))
        val second = agent.evaluateFrameAndSensor(Fixtures.frame(timestampMs = 1_001_000L), SensorPeak(1_001_000L, 0.9f))

        assertNotNull(first)
        assertNull(second)
        assertEquals(1, agent.acceptedDefects.size)
        assertEquals(1, agent.finishSession()!!.stats.duplicatesMerged)
    }

    @Test
    fun `visual only defect is upgraded when impact arrives`() {
        val agent = agent()
        agent.startSession(env())
        agent.evaluateFrameAndSensor(Fixtures.frame(timestampMs = 1_000_000L), sensorPeak = null)
        val upgraded = agent.evaluateFrameAndSensor(
            Fixtures.frame(timestampMs = 1_000_500L),
            SensorPeak(1_000_500L, 1.0f),
        )

        assertNotNull(upgraded)
        assertTrue(upgraded.confirmed)
        assertEquals(1, agent.acceptedDefects.size)
        assertTrue(agent.acceptedDefects.single().confirmed)
    }

    @Test
    fun `thermal telemetry throttles and then recovers`() {
        val agent = agent()
        agent.startSession(env())
        agent.onVehicleTelemetry(VehicleTelemetry(timestampMs = 1, cpuTempC = 43f, batteryPercent = 80))

        assertEquals(AgentState.THROTTLED, agent.state)
        assertEquals(15, agent.lastGovernorDecision.targetFps)

        agent.onVehicleTelemetry(VehicleTelemetry(timestampMs = 2, cpuTempC = 38f, batteryPercent = 80))
        assertEquals(AgentState.SCANNING, agent.state)
        assertEquals(30, agent.lastGovernorDecision.targetFps)
    }

    @Test
    fun `low battery produces warning`() {
        val agent = agent()
        agent.startSession(env(battery = 80))
        agent.onVehicleTelemetry(VehicleTelemetry(timestampMs = 1, cpuTempC = 35f, batteryPercent = 15))

        assertTrue(events.any { it is AgentEvent.BatteryLow })
        assertTrue(
            events.filterIsInstance<AgentEvent.VoicePrompt>().any { it.message == CoreMessages.DEFAULT.batteryWarning },
        )
    }

    @Test
    fun `ten dirty frames trigger lens warning`() {
        val agent = agent()
        agent.startSession(env())
        repeat(10) {
            agent.evaluateFrameAndSensor(Fixtures.frame(detection = null, contrast = 0.01f))
        }

        assertTrue(events.any { it is AgentEvent.LensDirty })
        assertTrue(
            events.filterIsInstance<AgentEvent.VoicePrompt>().any { it.message == CoreMessages.DEFAULT.lensWarning },
        )
    }

    @Test
    fun `frames without location are rejected`() {
        val agent = agent()
        agent.startSession(env())
        val record = agent.evaluateFrameAndSensor(Fixtures.frame(location = null), SensorPeak(1_000_000L, 1f))
        assertNull(record)
    }

    @Test
    fun `throttled agent skips every second frame`() {
        val agent = agent()
        agent.startSession(env())
        agent.onVehicleTelemetry(VehicleTelemetry(timestampMs = 1, cpuTempC = 44f, batteryPercent = 80))

        var accepted = 0
        repeat(10) { i ->
            val r = agent.evaluateFrameAndSensor(
                Fixtures.frame(timestampMs = 1_000_000L + i * 1000L),
                SensorPeak(1_000_000L + i * 1000L, 0.9f),
            )
            if (r != null) accepted++
        }
        // 5 из 10 кадров проанализированы; часть отсеяна дедупликацией по гео.
        assertTrue(accepted in 1..5)
        assertTrue(agent.finishSession()!!.stats.framesSkippedByThrottle >= 5)
    }

    @Test
    fun `finish produces clusters and estimate`() {
        val agent = agent()
        agent.startSession(env())
        agent.evaluateFrameAndSensor(Fixtures.frame(), SensorPeak(1_000_000L, 0.9f))
        val data = agent.finishSession()
        assertNotNull(data)
        assertEquals(AgentState.POST_PROCESSING, agent.state)

        val report = PostProcessingAgent().process(data)
        assertEquals(1, report.clusters.size)
        assertTrue(report.clusters.single().confirmed)
        assertTrue(report.estimate.totalMillingVolumeM3 > 0)
        assertTrue(report.estimate.totalRub > 0)

        agent.postProcessingDone()
        assertEquals(AgentState.IDLE, agent.state)
    }

    @Test
    fun `frames ignored outside scanning state`() {
        val agent = agent()
        assertNull(agent.evaluateFrameAndSensor(Fixtures.frame(), SensorPeak(1_000_000L, 1f)))
    }
}
