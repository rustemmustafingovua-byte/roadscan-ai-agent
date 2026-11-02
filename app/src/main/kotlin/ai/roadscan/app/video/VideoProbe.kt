package ai.roadscan.app.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Паспорт видеофайла. Размеры — уже «экранные» (с учётом поворота камеры):
 * вертикальное видео с телефона приходит как 1080×1920, а не 1920×1080.
 */
data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val fps: Float,
) {
    val aspect: Float get() = if (height == 0) 16f / 9f else width.toFloat() / height
    val durationLabel: String get() = formatDuration(durationMs)

    /** «1280×720 · 25 fps»; частоту кадров показываем только если её удалось узнать. */
    val resolutionLabel: String
        get() = if (fps >= 1f) "${width}×${height} · ${fps.toInt()} fps" else "${width}×${height}"

    companion object {
        fun formatDuration(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }
}

object VideoProbe {

    fun probe(context: Context, uri: Uri): VideoInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            val rawWidth = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return null
            val rawHeight = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return null
            val rotation = (
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                ).let { ((it % 360) + 360) % 360 }
            val captureFps = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
                ?.takeIf { it in 1f..240f }
            // У клипов из мессенджеров и монтажек capture frame rate отсутствует —
            // тогда берём реальную частоту дорожки, а не выдуманные 30 fps.
            val fps = captureFps ?: trackFps(context, uri)
            val swapped = rotation == 90 || rotation == 270
            VideoInfo(
                uri = uri,
                displayName = queryName(context, uri, retriever),
                durationMs = duration,
                width = if (swapped) rawHeight else rawWidth,
                height = if (swapped) rawWidth else rawHeight,
                rotationDegrees = rotation,
                fps = fps,
            )
        } catch (error: Throwable) {
            android.util.Log.w("RoadScanVideo", "не удалось прочитать видео $uri", error)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryName(
        context: Context,
        uri: Uri,
        retriever: MediaMetadataRetriever,
    ): String {
        val raw = if (uri.scheme == "file") {
            uri.lastPathSegment
        } else {
            val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
            runCatching {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
        }?.substringAfterLast('/')

        // Photo Picker отдаёт внутренний id («1000000018.mp4») вместо имени файла.
        val looksLikeId = raw.isNullOrBlank() ||
            raw.substringBeforeLast('.').let { stem -> stem.isNotEmpty() && stem.all { it.isDigit() } }
        if (!looksLikeId) return raw!!

        val untitled = runCatching { context.getString(ai.roadscan.app.R.string.video_untitled) }
            .getOrDefault("Video")
        val stamp = captureStamp(retriever) ?: return untitled
        return "$untitled $stamp"
    }

    private fun captureStamp(retriever: MediaMetadataRetriever): String? {
        val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            ?: return null
        return runCatching {
            val parsed: Date = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(raw)
                ?: return null
            // У копий без даты съёмки тег равен эпохе (1904/1970) — такую подпись не показываем.
            if (parsed.time < MIN_PLAUSIBLE_DATE_MS) return null
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(parsed)
        }.getOrNull()
    }

    private val MIN_PLAUSIBLE_DATE_MS = 946_684_800_000L // 2000-01-01

    /** Реальная частота кадров видеодорожки: 0, если метаданных нет. */
    private fun trackFps(context: Context, uri: Uri): Float {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) continue
                val fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }
                    .recoverCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }
                    .getOrNull() ?: continue
                if (fps in 1..240) return fps.toFloat()
            }
            0f
        } catch (_: Throwable) {
            0f
        } finally {
            runCatching { extractor.release() }
        }
    }
}
