package ai.roadscan.app.perception

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import ai.roadscan.core.agent.RoadScanAgent
import ai.roadscan.core.model.SensorSample

/**
 * Акселерометр: сенсор отдаёт события с максимальной частотой, мы прореживаем
 * поток до ~200 Гц, чтобы буфер не разрастался и CPU не грелся.
 */
class SensorCollector(
    context: Context,
    private val agent: RoadScanAgent,
    private val targetHz: Int = 200,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val minIntervalNs = 1_000_000_000L / targetHz
    private var lastTimestampNs = 0L

    val available: Boolean get() = accelerometer != null

    fun start() {
        lastTimestampNs = 0L
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.timestamp - lastTimestampNs < minIntervalNs) return
        lastTimestampNs = event.timestamp
        val values = event.values
        agent.onSensorSample(
            SensorSample(
                timestampMs = event.timestamp / 1_000_000L,
                zGForce = values[2],
                xGForce = values[0],
                yGForce = values[1],
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
