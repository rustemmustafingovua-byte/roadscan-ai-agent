package ai.roadscan.app.diagnostics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.StatFs
import ai.roadscan.app.location.LocationCollector
import ai.roadscan.app.perception.YoloV8Detector
import ai.roadscan.app.telemetry.TelemetryReader
import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.license.LicenseState

/**
 * Проверка готовности комплекта перед сменой.
 *
 * Оператор перед выездом должен за одну кнопку увидеть, что готово к работе:
 * модель загрузилась, камера и датчик доступны, GNSS ловит, место и заряд в норме,
 * лицензия активна. Любая неисправность здесь — это «смена сорвётся в поле».
 */
data class DiagnosticCheck(
    val id: String,
    val title: String,
    val ok: Boolean,
    /** Подробности: значение, которое увидел оператор. */
    val detail: String,
    /** Проблема блокирует смену (в отличие от предупреждения). */
    val blocking: Boolean = false,
)

object Diagnostics {

    /**
     * Выполняет проверки. Всё офлайн: ни один запрос не уходит в сеть.
     */
    fun run(
        context: Context,
        detector: YoloV8Detector,
        location: LocationCollector,
        telemetry: TelemetryReader,
        license: LicenseState,
        config: AgentConfig = AgentConfig.DEFAULT,
        sessionCount: Int = 0,
    ): List<DiagnosticCheck> {
        val checks = mutableListOf<DiagnosticCheck>()

        // --- модель ---
        val ready = detector.isReady
        checks += DiagnosticCheck(
            id = "model",
            title = "Модель детекции",
            ok = ready,
            detail = if (ready) {
                "загружена, вход ${detector.resolvedInputSize}px, ${detector.backend.name}"
            } else {
                "не загрузилась — анализ видео невозможен"
            },
            blocking = true,
        )

        // --- камера ---
        val cameras = runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            manager.cameraIdList.size
        }.getOrDefault(0)
        checks += DiagnosticCheck(
            id = "camera",
            title = "Камера",
            ok = cameras > 0,
            detail = if (cameras > 0) "доступна камер: $cameras" else "камера не найдена",
            blocking = true,
        )

        // --- датчик ускорения ---
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        checks += DiagnosticCheck(
            id = "accelerometer",
            title = "Датчик ускорения",
            ok = accelerometer != null,
            detail = if (accelerometer != null) {
                "доступен, минимальный интервал ${accelerometer.minDelay} мкс"
            } else {
                "не найден — сенсорная фузия и подтверждение удара не работают"
            },
            blocking = true,
        )

        // --- GNSS ---
        val accuracy = location.accuracyM
        val gpsOk = !accuracy.isNaN() && accuracy <= config.gpsAccuracyWarnM
        checks += DiagnosticCheck(
            id = "gnss",
            title = "GNSS",
            ok = gpsOk,
            detail = when {
                accuracy.isNaN() -> "фиксации нет — проверьте, включён ли GPS"
                gpsOk -> "точность ±${"%.1f".format(accuracy)} м"
                else -> "точность ±${"%.1f".format(accuracy)} м (нужно ≤ ${config.gpsAccuracyWarnM} м)"
            },
            blocking = false,
        )

        // --- место ---
        val freeMb = StatFs(context.filesDir.absolutePath).availableBytes / 1_000_000
        val diskOk = freeMb >= config.minFreeDiskMb
        checks += DiagnosticCheck(
            id = "disk",
            title = "Место на диске",
            ok = diskOk,
            detail = if (diskOk) {
                "свободно $freeMb МБ"
            } else {
                "свободно $freeMb МБ, нужно ≥ ${config.minFreeDiskMb} МБ"
            },
            blocking = true,
        )

        // --- батарея и нагрев ---
        val snapshot = telemetry.snapshot()
        val batteryOk = snapshot.batteryPercent > config.batteryLowPercent
        checks += DiagnosticCheck(
            id = "battery",
            title = "Батарея",
            ok = batteryOk,
            detail = "${snapshot.batteryPercent}%, ${"%.0f".format(snapshot.batteryTempC)} °C",
            blocking = false,
        )

        val temp = snapshot.cpuTempC
        val tempOk = temp.isNaN() || temp < config.socTempThrottleC
        checks += DiagnosticCheck(
            id = "thermal",
            title = "Нагрев процессора",
            ok = tempOk,
            detail = if (temp.isNaN()) "нет данных" else "${"%.0f".format(temp)} °C",
            blocking = false,
        )

        // --- лицензия ---
        val licensed = license.isLicensed(System.currentTimeMillis())
        checks += DiagnosticCheck(
            id = "license",
            title = "Лицензия",
            ok = licensed,
            detail = if (licensed) {
                license.summary(System.currentTimeMillis(), ai.roadscan.core.i18n.CoreMessages.DEFAULT)
            } else {
                "пробный период или документы с водяным знаком"
            },
            blocking = false,
        )

        // --- хранилище ---
        checks += DiagnosticCheck(
            id = "storage",
            title = "Сохранённые смены",
            ok = true,
            detail = "$sessionCount шт. в базе",
            blocking = false,
        )

        return checks
    }
}
