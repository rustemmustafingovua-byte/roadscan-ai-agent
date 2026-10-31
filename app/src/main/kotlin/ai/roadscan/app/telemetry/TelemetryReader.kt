package ai.roadscan.app.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import ai.roadscan.app.location.LocationCollector
import ai.roadscan.core.model.VehicleTelemetry

/**
 * Системная телеметрия: батарея, температура SoC, свободный диск, GPS, скорость.
 * Thermal API даёт качественную оценку нагрева, батарея — фактическую температуру.
 */
class TelemetryReader(
    private val context: Context,
    private val location: LocationCollector,
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun snapshot(): VehicleTelemetry {
        val battery = batteryState()
        val freeMb = StatFs(context.filesDir.absolutePath).availableBytes / 1_000_000
        return VehicleTelemetry(
            timestampMs = System.currentTimeMillis(),
            speedKmh = location.speedKmh,
            gpsAccuracyM = location.accuracyM,
            cpuTempC = socTemperatureC(battery.tempC),
            batteryPercent = battery.percent,
            batteryTempC = battery.tempC,
            charging = battery.charging,
            freeDiskMb = freeMb,
        )
    }

    private data class BatteryState(val percent: Int, val tempC: Float, val charging: Boolean)

    private fun batteryState(): BatteryState {
        val intent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) ?: 300
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        return BatteryState(
            percent = percent,
            tempC = tempTenths / 10f,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private fun socTemperatureC(batteryTempC: Float): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thermal = when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> 35f
                PowerManager.THERMAL_STATUS_LIGHT -> 40f
                PowerManager.THERMAL_STATUS_MODERATE -> 43f
                PowerManager.THERMAL_STATUS_SEVERE -> 46f
                PowerManager.THERMAL_STATUS_CRITICAL -> 49f
                PowerManager.THERMAL_STATUS_EMERGENCY -> 52f
                PowerManager.THERMAL_STATUS_SHUTDOWN -> 55f
                else -> batteryTempC
            }
            return maxOf(thermal, batteryTempC)
        }
        return maxOf(batteryTempC, 35f)
    }
}
