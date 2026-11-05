package ai.roadscan.app.setup

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import ai.roadscan.core.calibration.CameraOptics
import ai.roadscan.core.calibration.CalibrationProfile
import ai.roadscan.core.calibration.DeviceTuner
import ai.roadscan.core.calibration.DeviceTuning
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Автоподготовка комплекта: всё, что раньше оператор измерял руками.
 *
 * Одна кнопка «Автонастройка»:
 *  1. берёт фокус и размер сенсора из характеристик камеры (Android);
 *  2. измеряет наклон кронштейна по датчику тяжотения (телефон уже стоит
 *     в кронштейне — оператор просто не трогает его пару секунд);
 *  3. замеряет реальную задержку инференса на этом устройстве;
 *  4. подбирает профиль работы (потоки, FPS, разрешение анализа).
 *
 * Возвращает готовый профиль калибровки и тюнинг устройства.
 */
class AutoSetupRunner(private val context: Context) {

    data class Result(
        val calibration: CalibrationProfile,
        val tuning: DeviceTuning,
        /** Понятное описание того, что получилось. */
        val summary: String,
    )

    /**
     * Выполняет подготовку. [measureInferenceMs] — замер инференса
     * (средняя задержка детектора на пробных кадрах, мс); вызывается
     * из того же фонового потока.
     */
    fun run(
        cameraHeightM: Float = 1.3f,
        measureInferenceMs: () -> Double = { 50.0 },
    ): Result {
        val optics = readCameraOptics()
        val pitch = readPitchDeg()
        val inferenceMs = runCatching { measureInferenceMs() }.getOrDefault(50.0)

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val ramMb = totalRamMb()
        val tuning = DeviceTuner.recommend(cores, ramMb, inferenceMs)

        val calibration = CalibrationProfile(
            name = "Автонастройка",
            cameraHeightM = cameraHeightM,
            cameraPitchDeg = pitch,
            focalLengthPx = optics,
            vehicle = Build.MODEL ?: "",
            calibratedAtMs = System.currentTimeMillis(),
        )

        val summary = buildString {
            append("Оптика: ")
            append(if (optics != null) "фокус ${"%.0f".format(optics)} px" else "фокус неизвестен (ручной ввод)")
            append(", наклон ${"%.1f".format(pitch)}°")
            append("; устройство: $cores ядер, ${ramMb / 1024} ГБ")
            append(", инференс ${"%.0f".format(inferenceMs)} мс")
            append(" → ${tuning.analysisFps} FPS, ${tuning.analysisWidthPx} px, ${tuning.inferenceThreads} потоков")
        }
        return Result(calibration, tuning, summary)
    }

    /** Фокус в пикселях для кадра анализа из характеристик камеры; null — если недоступно. */
    private fun readCameraOptics(): Float? = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return null
        val chars = manager.getCameraCharacteristics(backId)
        val focalMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: return null
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        // Кадр анализа — 1280 px по длинной стороне: под него переводим фокус.
        val longSideMm = maxOf(sensor.width, sensor.height)
        CameraOptics.focalLengthPx(focalMm, longSideMm, 1280)
    }.getOrNull()

    /**
     * Наклон по тяжотению: короткий замер на датчике, пока телефон
     * неподвижен в кронштейне. NaN — датчик недоступен, оставляем дефолт.
     */
    private fun readPitchDeg(): Float {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return 8f

        val samples = mutableListOf<FloatArray>()
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                synchronized(samples) {
                    samples += event.values.copyOf()
                    if (samples.size >= 20) latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        runCatching { latch.await(2, TimeUnit.SECONDS) }
        manager.unregisterListener(listener)

        val average = synchronized(samples) {
            if (samples.isEmpty()) return 8f
            val g = floatArrayOf(0f, 0f, 0f)
            samples.forEach { s ->
                g[0] += s[0]; g[1] += s[1]; g[2] += s[2]
            }
            FloatArray(3) { g[it] / samples.size }
        }
        val pitch = CameraOptics.pitchDegFromGravity(average[0], average[1], average[2])
        return if (pitch.isNaN()) 8f else pitch
    }

    private fun totalRamMb(): Int = runCatching {
        val info = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getMemoryInfo(info)
        (info.totalMem / (1024 * 1024)).toInt()
    }.getOrDefault(4096)

    companion object {
        /** Длина стороны кадра анализа, для которой считается фокус. */
        const val ANALYSIS_LONG_SIDE_PX = 1280

        /** Проверка формулы тяжотения на «живом» векторе — для тестов и диагностики. */
        fun pitchFromSensorSample(x: Float, y: Float, z: Float): Float =
            CameraOptics.pitchDegFromGravity(x, y, z)

        fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
    }
}
