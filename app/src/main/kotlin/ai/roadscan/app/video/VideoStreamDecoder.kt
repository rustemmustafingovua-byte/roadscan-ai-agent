package ai.roadscan.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlin.math.min

/**
 * Потоковый декодер видео: MediaExtractor + MediaCodec.
 *
 * `MediaMetadataRetriever.getFrameAtTime` на каждый кадр заново ищет ключевой
 * кадр — на длинном ролике это ~1–2 секунды на кадр. Здесь кодек открывается
 * один раз, а seek выполняется только при больших переходах по времени,
 * поэтому анализ 10-минутного видео занимает десятки секунд, а не часы.
 *
 * Кадр отдаётся как переиспользуемый Bitmap: вызывающая сторона обязана
 * использовать его сразу и не хранить ссылку.
 */
class VideoStreamDecoder(
    context: Context,
    private val info: VideoInfo,
) : AutoCloseable {

    private val extractor = MediaExtractor()
    private val bufferInfo = MediaCodec.BufferInfo()
    private val codec: MediaCodec

    private var reusable: Bitmap? = null
    private var rotated: Bitmap? = null
    private var pixels: IntArray = IntArray(0)
    private var outWidth = 0
    private var outHeight = 0

    private var inputDone = false
    private var outputDone = false
    private var started = false

    /** Время последнего декодированного кадра, мс. */
    var currentTimeMs: Long = -1
        private set

    init {
        extractor.setDataSource(context, info.uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(index)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                trackIndex = index
                format = candidate
                break
            }
        }
        require(trackIndex >= 0 && format != null) { "no video track" }
        extractor.selectTrack(trackIndex)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        started = true
    }

    /**
     * Переход к [timeMs]. Декодирование начнётся с ближайшего ключевого кадра,
     * который не позже запрошенного времени.
     */
    fun seekTo(timeMs: Long) {
        val target = timeMs.coerceAtLeast(0)
        val closeEnough = currentTimeMs >= 0 &&
            currentTimeMs <= target + 250 &&
            target - currentTimeMs < SEEK_THRESHOLD_MS
        if (closeEnough) return
        extractor.seekTo(target * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        runCatching { codec.flush() }
        inputDone = false
        outputDone = false
        currentTimeMs = -1
    }

    /** Первый кадр с временем >= [timeMs]. */
    fun advanceTo(timeMs: Long): Bitmap? {
        if (outputDone) return null
        var guard = 0
        while (guard++ < MAX_FRAMES_PER_ADVANCE) {
            val frame = decodeNext() ?: return null
            if (currentTimeMs >= timeMs) return frame
        }
        return null
    }

    /** Следующий кадр подряд (для покадрового рендера). */
    fun nextFrame(): Bitmap? = if (outputDone) null else decodeNext()

    private fun decodeNext(): Bitmap? {
        feedInput()
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        return null
                    }
                    feedInput()
                    continue
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    outWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    outHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    continue
                }

                index >= 0 -> {
                    val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val size = bufferInfo.size
                    val bitmap = if (size > 0) {
                        val image = runCatching { codec.getOutputImage(index) }.getOrNull()
                        if (image != null) {
                            runCatching { toBitmap(image) }.getOrNull().also { image.close() }
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                    currentTimeMs = bufferInfo.presentationTimeUs / 1_000L
                    codec.releaseOutputBuffer(index, false)
                    if (endOfStream) outputDone = true
                    if (bitmap != null) return bitmap
                    if (endOfStream) return null
                    continue
                }

                else -> continue
            }
        }
    }

    private fun feedInput() {
        if (inputDone) return
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return
            val buffer = codec.getInputBuffer(index) ?: return
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                inputDone = true
                return
            }
            codec.queueInputBuffer(index, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    /** YUV (I420/NV12) → ARGB с учётом cropRect, rowStride и pixelStride. */
    private fun toBitmap(image: Image): Bitmap {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val bitmap = ensureBitmap(width, height)
        if (pixels.size != width * height) pixels = IntArray(width * height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var out = 0
        var row = 0
        while (row < height) {
            val yRow = (crop.top + row) * yRowStride
            val nextRow = min(row + 1, height - 1)
            val yRowNext = (crop.top + nextRow) * yRowStride
            val uvRow = ((crop.top + row) / 2) * uvRowStride
            var col = 0
            while (col < width) {
                val uIndex = uvRow + ((crop.left + col) / 2) * uPixelStride
                val vIndex = uvRow + ((crop.left + col) / 2) * vPixelStride
                val u = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                val v = (vBuffer.get(vIndex).toInt() and 0xFF) - 128

                val y0 = (yBuffer.get(yRow + (crop.left + col) * yPixelStride).toInt() and 0xFF) - 16
                pixels[out] = yuvToColor(y0, u, v)
                if (col + 1 < width) {
                    val y1 = (yBuffer.get(yRow + (crop.left + col + 1) * yPixelStride).toInt() and 0xFF) - 16
                    pixels[out + 1] = yuvToColor(y1, u, v)
                }
                if (row + 1 < height) {
                    val y2 = (yBuffer.get(yRowNext + (crop.left + col) * yPixelStride).toInt() and 0xFF) - 16
                    pixels[out + width] = yuvToColor(y2, u, v)
                    if (col + 1 < width) {
                        val y3 = (yBuffer.get(yRowNext + (crop.left + col + 1) * yPixelStride).toInt() and 0xFF) - 16
                        pixels[out + width + 1] = yuvToColor(y3, u, v)
                    }
                }
                col += 2
                out += 2
            }
            if (row + 1 < height) {
                out += width
                row += 2
            } else {
                row += 1
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return applyRotation(bitmap)
    }

    private fun yuvToColor(y: Int, u: Int, v: Int): Int {
        val r = clamp((298 * y + 409 * v + 128) shr 8)
        val g = clamp((298 * y - 100 * u - 208 * v + 128) shr 8)
        val b = clamp((298 * y + 516 * u + 128) shr 8)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(value: Int): Int = when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }

    private fun ensureBitmap(width: Int, height: Int): Bitmap {
        val current = reusable
        if (current != null && current.width == width && current.height == height && !current.isRecycled) {
            return current
        }
        current?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { reusable = it }
    }

    /**
     * Телефоны пишут вертикальное видео как горизонтальные кадры + флаг
     * поворота. Кадр уже может быть повёрнут прошивкой — определяем по
     * фактическим габаритам.
     */
    private fun applyRotation(bitmap: Bitmap): Bitmap {
        val rotation = info.rotationDegrees
        if (rotation % 360 == 0) return bitmap
        if (bitmap.width == info.width && bitmap.height == info.height) return bitmap
        val previous = rotated
        if (previous != null && !previous.isRecycled) previous.recycle()
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        rotated = result
        return result
    }

    override fun close() {
        if (started) {
            runCatching { codec.stop() }
            started = false
        }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        reusable?.let { if (!it.isRecycled) it.recycle() }
        rotated?.let { if (!it.isRecycled && it !== reusable) it.recycle() }
        reusable = null
        rotated = null
        pixels = IntArray(0)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val SEEK_THRESHOLD_MS = 1_500L
        const val MAX_FRAMES_PER_ADVANCE = 600
    }
}
