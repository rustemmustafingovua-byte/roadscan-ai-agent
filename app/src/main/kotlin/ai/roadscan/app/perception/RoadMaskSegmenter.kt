package ai.roadscan.app.perception

import android.content.Context
import android.graphics.Bitmap
import ai.roadscan.core.perception.RoadSurfaceFilter
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Сегментация дорожного полотна (TwinLiteNet, MIT) — маска для привязки
 * детекций к дороге.
 *
 * Модель берёт 640×360 RGB и отдаёт два слоя (полотно и разметка).
 * Нас интересует полотно: пиксель маски = 1, если слой дороги активен.
 * Маска строится на малом кадре [WIDTH]×[height], как в эталонном RoadScan:
 * этого достаточно для отбраковки находок вне проезжей части.
 *
 * Модель лёгкая (~3 МБ, int8-веса), но инференс всё же стоит времени,
 * поэтому маска пересчитывается раз в [cacheFrames] кадров и кэшируется.
 */
class RoadMaskSegmenter(
    context: Context,
    private val assetName: String = "road_mask_twinlite.tflite",
    private val cacheFrames: Int = 3,
    numThreads: Int = 2,
) : Closeable {

    private var interpreter: Interpreter? = null
    private val inputBuffer: ByteBuffer
    private val output0: ByteBuffer
    private val output1: ByteBuffer
    private val pixels = IntArray(MODEL_W * MODEL_H)

    /** Кэш маски: сетка кадра (480 × H) и счётчик кадров. */
    private var mask: ByteArray? = null
    private var maskWidth = 0
    private var maskHeight = 0
    private var framesSinceMask = Int.MAX_VALUE
    private var failures = 0
    private var loggedOnce = false

    init {
        interpreter = runCatching {
            val bytes = context.assets.open(assetName).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .apply { put(bytes); rewind() }
            Interpreter(model, Interpreter.Options().setNumThreads(numThreads))
        }.getOrNull()
        inputBuffer = ByteBuffer
            .allocateDirect(4 * 3 * MODEL_H * MODEL_W)
            .order(ByteOrder.nativeOrder())
        val outSize = 4 * 2 * MODEL_H * MODEL_W
        output0 = ByteBuffer.allocateDirect(outSize).order(ByteOrder.nativeOrder())
        output1 = ByteBuffer.allocateDirect(outSize).order(ByteOrder.nativeOrder())
    }

    val isReady: Boolean get() = interpreter != null

    /**
     * Маска дороги для кадра: ByteArray размером [width]×[height]
     * (0 — не дорога, 1 — дорога). null — модель недоступна (тогда
     * offroad-проверка в фильтре пропускается).
     */
    @Synchronized
    fun roadMask(bitmap: Bitmap, width: Int, height: Int): ByteArray? {
        val interp = interpreter ?: return null
        if (mask != null && maskWidth == width && maskHeight == height && framesSinceMask < cacheFrames) {
            framesSinceMask++
            return mask
        }
        framesSinceMask = 0
        val built = runCatching { build(interp, bitmap, width, height) }
        if (built.isFailure) {
            // Модель один раз отказалась — дальше без маски, без спама в лог.
            failures++
            if (failures >= 3) {
                runCatching { interp.close() }
                interpreter = null
            }
            return mask
        }
        maskWidth = width
        maskHeight = height
        mask = built.getOrNull()
        if (mask != null && !loggedOnce) {
            loggedOnce = true
            var road = 0
            for (b in mask!!) if (b != 0.toByte()) road++
            android.util.Log.d(
                "RoadScanGate",
                "маска дороги построена: ${width}x$height, доля полотна ${(road * 100 / mask!!.size)}%",
            )
        }
        return mask
    }

    private fun build(interp: Interpreter, bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, MODEL_W, MODEL_H, true)
        scaled.getPixels(pixels, 0, MODEL_W, 0, 0, MODEL_W, MODEL_H)
        if (scaled !== bitmap) scaled.recycle()

        inputBuffer.rewind()
        for (channel in 0..2) {
            val shift = 16 - channel * 8
            for (p in pixels) {
                inputBuffer.putFloat(((p shr shift) and 0xFF) / 255f)
            }
        }
        inputBuffer.rewind()

        val outputs = mutableMapOf<Int, Any>(
            0 to output0.apply { rewind() },
            1 to output1.apply { rewind() },
        )
        interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        output0.rewind()
        output1.rewind()
        val out0 = FloatBufferView(output0)
        val out1 = FloatBufferView(output1)

        // Логика эталона: слой 2 (дорога) активнее слоя 0/1 — пиксель дороги.
        val mask = ByteArray(width * height)
        for (y in 0 until height) {
            val sy = minOf(MODEL_H - 1, y * MODEL_H / height)
            for (x in 0 until width) {
                val sx = minOf(MODEL_W - 1, x * MODEL_W / width)
                val idx = sy * MODEL_W + sx
                // По эталону: слой 1 активнее слоя 0 — пиксель полотна.
                mask[y * width + x] = if (
                    out0.get(idx + STRIDE) > out0.get(idx) ||
                    out1.get(idx + STRIDE) > out1.get(idx)
                ) {
                    1
                } else {
                    0
                }
            }
        }
        return mask
    }

    /** Мелкая обёртка чтения float из буфера выхода (2 слоя × H × W). */
    private class FloatBufferView(private val buffer: ByteBuffer) {
        fun get(index: Int): Float = buffer.getFloat(index * 4)
        private val STRIDE = MODEL_W * MODEL_H
    }

    override fun close() {
        runCatching { interpreter?.close() }
        interpreter = null
    }

    companion object {
        private const val MODEL_W = 640
        private const val MODEL_H = 360
        private const val STRIDE = MODEL_W * MODEL_H
    }
}
