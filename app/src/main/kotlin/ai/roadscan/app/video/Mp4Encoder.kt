package ai.roadscan.app.video

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Кодирование готовых кадров в H.264/MP4 без звука и без внешних библиотек.
 *
 * Кадры подаются как ARGB Bitmap, конвертируются в YUV 4:2:0 прямо в буферы
 * кодека (Image API), поэтому не нужен ни OpenGL, ни промежуточный файл.
 */
class Mp4Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val output: File,
) : AutoCloseable {

    private val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val bufferInfo = MediaCodec.BufferInfo()
    private val pixels = IntArray(width * height)
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameIndex = 0L
    private var closed = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode(bitmap: Bitmap) {
        val ptsUs = frameIndex * 1_000_000L / fps
        frameIndex++

        // Кадр нельзя терять: пропуск кадра сокращает ролик и ломает тайминг
        // титров. Ждём свободный входной буфер, попутно забирая готовые кадры.
        var attempts = 0
        while (attempts++ < MAX_INPUT_ATTEMPTS) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) {
                drain(endOfStream = false)
                continue
            }
            val image = runCatching { codec.getInputImage(inputIndex) }.getOrNull()
            if (image != null) {
                fillImage(image, bitmap)
                codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, ptsUs, 0)
            } else {
                // Часть кодеков работает только через ByteBuffer — пишем I420 сами.
                val buffer = codec.getInputBuffer(inputIndex)
                if (buffer != null) {
                    fillBuffer(buffer, bitmap)
                    codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, ptsUs, 0)
                } else {
                    codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                }
            }
            break
        }
        drain(endOfStream = false)
    }

    fun finish() {
        var queued = false
        var attempts = 0
        while (!queued && attempts < 50) {
            attempts++
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    frameIndex * 1_000_000L / fps,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                queued = true
            }
        }
        if (queued) drain(endOfStream = true)
    }

    private fun drain(endOfStream: Boolean) {
        var idleRounds = 0
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    idleRounds++
                    if (idleRounds > 200) return
                }

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }

                else -> {
                    if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && bufferInfo.size > 0 && muxerStarted) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            return
                        }
                    }
                }
            }
        }
    }

    /**
     * ARGB → YUV 4:2:0 (BT.601, ограниченный диапазон — так ждёт аппаратный
     * энкодер). Плоскости пишутся с учётом rowStride/pixelStride: на разных
     * SoC раскладка NV12/I420 отличается.
     */
    private fun fillImage(image: Image, bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

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

        var index = 0
        for (y in 0 until height) {
            val yBase = y * yRowStride
            for (x in 0 until width) {
                val color = pixels[index++]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val luma = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(yBase + x * yPixelStride, luma.coerceIn(16, 235).toByte())
            }
        }

        for (y in 0 until height step 2) {
            val uvRow = (y / 2) * uvRowStride
            for (x in 0 until width step 2) {
                val topLeft = pixels[y * width + x]
                val topRight = pixels[y * width + minOf(x + 1, width - 1)]
                val bottomLeft = pixels[minOf(y + 1, height - 1) * width + x]
                val bottomRight = pixels[minOf(y + 1, height - 1) * width + minOf(x + 1, width - 1)]

                val r = (((topLeft shr 16) and 0xFF) + ((topRight shr 16) and 0xFF) +
                    ((bottomLeft shr 16) and 0xFF) + ((bottomRight shr 16) and 0xFF)) / 4
                val g = (((topLeft shr 8) and 0xFF) + ((topRight shr 8) and 0xFF) +
                    ((bottomLeft shr 8) and 0xFF) + ((bottomRight shr 8) and 0xFF)) / 4
                val b = ((topLeft and 0xFF) + (topRight and 0xFF) +
                    (bottomLeft and 0xFF) + (bottomRight and 0xFF)) / 4

                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                val uvIndex = uvRow + (x / 2) * uPixelStride
                uBuffer.put(uvIndex, u.coerceIn(16, 240).toByte())
                vBuffer.put(uvIndex, v.coerceIn(16, 240).toByte())
            }
        }
    }

    /** Резервный путь: плоскости I420 подряд в ByteBuffer кодека. */
    private fun fillBuffer(buffer: ByteBuffer, bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val ySize = width * height
        val uvSize = ySize / 4
        buffer.clear()

        for (index in 0 until ySize) {
            val color = pixels[index]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val luma = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            buffer.put(luma.coerceIn(16, 235).toByte())
        }

        val chroma = ByteArray(uvSize * 2)
        var uIndex = 0
        var vIndex = uvSize
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val topLeft = pixels[y * width + x]
                val topRight = pixels[y * width + minOf(x + 1, width - 1)]
                val bottomLeft = pixels[minOf(y + 1, height - 1) * width + x]
                val bottomRight = pixels[minOf(y + 1, height - 1) * width + minOf(x + 1, width - 1)]
                val r = (((topLeft shr 16) and 0xFF) + ((topRight shr 16) and 0xFF) +
                    ((bottomLeft shr 16) and 0xFF) + ((bottomRight shr 16) and 0xFF)) / 4
                val g = (((topLeft shr 8) and 0xFF) + ((topRight shr 8) and 0xFF) +
                    ((bottomLeft shr 8) and 0xFF) + ((bottomRight shr 8) and 0xFF)) / 4
                val b = ((topLeft and 0xFF) + (topRight and 0xFF) +
                    (bottomLeft and 0xFF) + (bottomRight and 0xFF)) / 4
                chroma[uIndex++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
                    .coerceIn(16, 240).toByte()
                chroma[vIndex++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
                    .coerceIn(16, 240).toByte()
                x += 2
            }
            y += 2
        }
        buffer.put(chroma, 0, uvSize)
        buffer.put(chroma, uvSize, uvSize)
    }

    private fun bitrate(): Int {
        val pixelCount = width.toLong() * height
        return (pixelCount * 6).toInt().coerceIn(1_500_000, 12_000_000)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer.stop() }
        runCatching { muxer.release() }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_INPUT_ATTEMPTS = 400
    }
}
