package ai.roadscan.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val confirmedCount: Int,
    val visualOnlyCount: Int,
    val totalAreaM2: Double,
    /**
     * Сумма сметы в валюте справочника цен (не обязательно рубли).
     * Имя колонки в БД — историческое `totalRub`, чтобы не требовать миграции.
     */
    @ColumnInfo(name = "totalRub") val total: Double,
    val createdAtMs: Long,
    /** Реквизиты объекта/договора (JSON ReportMeta). */
    @ColumnInfo(defaultValue = "") val metaJson: String = "",
    /** Профиль калибровки, с которым выполнена смена (JSON). */
    @ColumnInfo(defaultValue = "") val calibrationJson: String = "",
)

@Entity(
    tableName = "defects",
    indices = [Index("sessionId")],
)
data class DefectEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val type: String,
    val severity: Int,
    val confirmed: Boolean,
    val status: String,
    val areaM2: Double,
    val depthMm: Double,
    val visionConfidence: Float,
    val impactG: Float,
    val nearField: Boolean,
)

@Dao
interface RoadScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDefects(defects: List<DefectEntity>)

    @Transaction
    suspend fun saveSession(session: SessionEntity, defects: List<DefectEntity>) {
        insertSession(session)
        deleteDefects(session.id)
        insertDefects(defects)
    }

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT COUNT(*) FROM sessions")
    fun observeSessionCount(): Flow<Int>

    @Query("SELECT * FROM defects WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun defectsFor(sessionId: String): List<DefectEntity>

    @Query("DELETE FROM defects WHERE sessionId = :sessionId")
    suspend fun deleteDefects(sessionId: String)
}

@Database(
    entities = [SessionEntity::class, DefectEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class RoadScanDatabase : RoomDatabase() {
    abstract fun dao(): RoadScanDao

    companion object {
        /** v2: реквизиты документов и калибровка в сохранённой смене. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN metaJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sessions ADD COLUMN calibrationJson TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
