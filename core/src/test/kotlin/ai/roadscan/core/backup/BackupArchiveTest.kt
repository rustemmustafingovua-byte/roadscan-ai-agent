package ai.roadscan.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupArchiveTest {

    private fun tempDir(): File =
        createTempDir(prefix = "backup_test")

    private fun sqliteFile(dir: File, name: String = BackupArchive.DB_ENTRY): File {
        val file = File(dir, name)
        file.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(64))
        return file
    }

    @Test
    fun `roundtrip — архив пишется и читается обратно`() {
        val source = tempDir()
        val db = sqliteFile(source)
        val prefs = File(source, "settings.xml").apply { writeText("<map/>") }

        val bytes = ByteArrayOutputStream().also { out ->
            BackupArchive.write(
                out,
                listOf(
                    BackupArchive.DB_ENTRY to db,
                    BackupArchive.PREFS_ENTRY to prefs,
                    // отсутствующего файла быть не должно — пропускается
                    "roadscan.db-wal" to File(source, "roadscan.db-wal"),
                ),
            )
        }.toByteArray()

        val target = tempDir()
        val restored = BackupArchive.extract(ByteArrayInputStream(bytes), target)
        assertEquals(2, restored.size)
        assertTrue(db.readBytes().contentEquals(File(target, BackupArchive.DB_ENTRY).readBytes()))
        assertEquals("<map/>", File(target, BackupArchive.PREFS_ENTRY).readText())
        source.deleteRecursively()
        target.deleteRecursively()
    }

    @Test
    fun `архив без базы отклоняется`() {
        val source = tempDir()
        val bytes = ByteArrayOutputStream().also { out ->
            BackupArchive.write(out, listOf("readme.txt" to File(source, "x").apply { writeText("x") }))
        }.toByteArray()

        val error = assertFailsWith<IllegalStateException> {
            BackupArchive.extract(ByteArrayInputStream(bytes), tempDir())
        }
        assertTrue(error.message!!.contains(BackupArchive.DB_ENTRY))
        source.deleteRecursively()
    }

    @Test
    fun `не-sqlite файл вместо базы отклоняется`() {
        val source = tempDir()
        val fake = File(source, BackupArchive.DB_ENTRY).apply { writeText("это не база") }
        val bytes = ByteArrayOutputStream().also { out ->
            BackupArchive.write(out, listOf(BackupArchive.DB_ENTRY to fake))
        }.toByteArray()

        assertFailsWith<IllegalStateException> {
            BackupArchive.extract(ByteArrayInputStream(bytes), tempDir())
        }
        source.deleteRecursively()
    }

    @Test
    fun `zip-slip — выход за каталог назначения блокируется`() {
        val target = tempDir()
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("../../evil.txt"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        assertFailsWith<IllegalStateException> {
            BackupArchive.extract(ByteArrayInputStream(bytes), target)
        }
        assertTrue(!File(target.parentFile, "evil.txt").exists())
        target.deleteRecursively()
    }
}
