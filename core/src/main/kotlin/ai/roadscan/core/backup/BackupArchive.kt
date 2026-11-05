package ai.roadscan.core.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Архив резервной копии комплекса.
 *
 * Копия — это ровно те файлы, без которых данные не переживают
 * переустановку или переезд на новый телефон: база смен (сметы, фото,
 * ведомости) и настройки (калибровка, реквизиты, лицензия).
 *
 * Формат — обычный zip: архив открывается любым файловым менеджером,
 * содержимое можно осмотреть и руками.
 */
object BackupArchive {

    /** База данных (имя файла Room). */
    const val DB_ENTRY = "roadscan.db"

    /** Файл настроек приложения. */
    const val PREFS_ENTRY = "shared_prefs/roadscan.settings.xml"

    /** Магическая подпись sqlite (16 байт заголовка). */
    private const val SQLITE_HEADER = "SQLite format 3\u0000"

    /**
     * Записывает архив: пары «имя внутри архива → файл».
     * Отсутствующие файлы (например, пустой -wal) просто пропускаются.
     */
    fun write(out: OutputStream, entries: List<Pair<String, File>>) {
        ZipOutputStream(out.buffered()).use { zip ->
            entries.forEach { (name, file) ->
                if (!file.exists()) return@forEach
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Распаковывает архив в [targetDir] и возвращает положенные файлы.
     *
     * Проверки: база обязана быть настоящим sqlite (защита от подмены
     * битым файлом) и пути не должны выходить из каталога (zip-slip —
     * запись «../../» за пределы назначения).
     */
    fun extract(input: InputStream, targetDir: File): List<File> {
        val result = mutableListOf<File>()
        targetDir.mkdirs()
        val root = targetDir.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = File(targetDir, entry.name)
                if (!out.canonicalPath.startsWith(root)) {
                    error("небезопасный путь в архиве: ${entry.name}")
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
                result += out
            }
        }
        val db = result.find { it.name == DB_ENTRY } ?: error("в архиве нет базы $DB_ENTRY")
        checkSqlite(db)
        return result
    }

    /** Проверяет, что файл начинается с подписи sqlite. */
    fun checkSqlite(db: File) {
        val header = ByteArray(16)
        db.inputStream().use { stream ->
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            if (read < header.size) error("файл короче заголовка sqlite")
        }
        if (String(header, Charsets.US_ASCII) != SQLITE_HEADER) {
            error("файл не база sqlite")
        }
    }
}
