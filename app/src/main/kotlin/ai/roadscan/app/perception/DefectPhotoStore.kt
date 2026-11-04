package ai.roadscan.app.perception

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Хранилище снимков находок.
 *
 * Снимок снимается в момент обнаружения: через кадр камера перезапишет его.
 * Файл ложится в приватный каталог приложения — он переживает перезапуск
 * и попадает в ведомость и комплект документов.
 */
class DefectPhotoStore(private val context: Context) {

    /**
     * Сохраняет уменьшенный снимок кадра с находкой.
     *
     * @return путь к файлу или null, если запись не удалась
     */
    fun save(defectId: String, bitmap: Bitmap): String? {
        if (bitmap.isRecycled) return null
        return runCatching {
            val dir = File(context.filesDir, PHOTO_DIR).apply { mkdirs() }
            val file = File(dir, "$defectId.jpg")
            val scaled = downscale(bitmap, MAX_WIDTH)
            file.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            file.absolutePath
        }.getOrNull()
    }

    /** Удаляет снимки дефектов сессии (при отмене смены). */
    fun clearSession(sessionId: String) {
        runCatching {
            val dir = File(context.filesDir, PHOTO_DIR)
            dir.listFiles { file -> file.name.startsWith(sessionId) }?.forEach { it.delete() }
        }
    }

    private fun downscale(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth) return source
        val ratio = maxWidth.toFloat() / source.width
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, height, true)
    }

    private companion object {
        const val PHOTO_DIR = "defects"
        const val MAX_WIDTH = 960
        const val JPEG_QUALITY = 82
    }
}
