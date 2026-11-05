package ai.roadscan.app.data

import android.content.Context
import android.net.Uri
import ai.roadscan.core.backup.BackupArchive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Резервное копирование данных комплекса одним файлом.
 *
 * Экспорт: база смен (сметы, ведомости, фото находок) + настройки
 * (калибровка, реквизиты, лицензия) → zip в cache/exports, дальше
 * обычный share (почта, облако, файловый менеджер, Telegram).
 *
 * Импорт: архив раскладывается в staging и помечается флагом; подмена
 * файлов происходит [applyPendingRestore] в самом начале инициализации
 * приложения — до того, как Room и SharedPreferences открыты (в процессе
 * их переключить нельзя). Поэтому после импорта процесс перезапускается.
 */
class BackupManager(private val context: Context) {

    /**
     * Создаёт архив копии. [checkpoint] — сброс WAL в основной файл базы
     * (вызывается до копирования, чтобы db не отстал от журнала).
     */
    fun create(checkpoint: () -> Unit): File {
        runCatching(checkpoint)
        val db = context.getDatabasePath(DB_NAME)
        if (!db.exists()) error("база ещё не создана")
        val prefs = File(context.applicationInfo.dataDir, "shared_prefs/${BackupArchive.PREFS_ENTRY.substringAfter('/')}")
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "roadscan_backup_$stamp.zip")
        out.outputStream().use { stream ->
            BackupArchive.write(
                stream,
                listOf(
                    BackupArchive.DB_ENTRY to db,
                    "$DB_NAME-wal" to File(db.path + "-wal"),
                    "$DB_NAME-shm" to File(db.path + "-shm"),
                    BackupArchive.PREFS_ENTRY to prefs,
                ),
            )
        }
        return out
    }

    /**
     * Раскладывает архив в staging и ставит флаг восстановления.
     * Файлы подменятся при следующем старте процесса.
     */
    fun stageRestore(uri: Uri) {
        val staging = File(context.filesDir, "restore_staging")
        staging.deleteRecursively()
        context.contentResolver.openInputStream(uri)?.use { input ->
            BackupArchive.extract(input, staging)
        } ?: error("файл не открылся")
        File(context.filesDir, PENDING_FLAG).writeText(staging.absolutePath)
    }

    companion object {
        const val DB_NAME = "roadscan.db"
        private const val PENDING_FLAG = "restore.pending"

        /**
         * Переносит восстановленные файлы на место. Вызывается первым
         * шагом инициализации приложения — пока данные ещё не открыты.
         * Возвращает true, если восстановление применено (нужен перезапуск
         * процесса, чтобы он открыл уже подменённые файлы).
         */
        fun applyPendingRestore(context: Context): Boolean {
            val marker = File(context.filesDir, PENDING_FLAG)
            if (!marker.exists()) return false
            return runCatching {
                val staging = File(marker.readText())
                val db = context.getDatabasePath(DB_NAME)
                db.parentFile?.mkdirs()
                // Журнал от старой базы несовместим с чужим main-файлом.
                File(db.path + "-wal").delete()
                File(db.path + "-shm").delete()
                File(staging, BackupArchive.DB_ENTRY).copyTo(db, overwrite = true)
                listOf("-wal", "-shm").forEach { suffix ->
                    val journal = File(staging, DB_NAME + suffix)
                    if (journal.exists()) journal.copyTo(File(db.path + suffix), overwrite = true)
                }
                val prefs = File(staging, BackupArchive.PREFS_ENTRY)
                if (prefs.exists()) {
                    val target = File(
                        context.applicationInfo.dataDir,
                        "shared_prefs/${BackupArchive.PREFS_ENTRY.substringAfter('/')}",
                    )
                    target.parentFile?.mkdirs()
                    prefs.copyTo(target, overwrite = true)
                }
                staging.deleteRecursively()
                marker.delete()
                true
            }.getOrDefault(false)
        }
    }
}
