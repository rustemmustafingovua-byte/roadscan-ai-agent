package ai.roadscan.app

import android.app.Application
import ai.roadscan.app.data.RoadScanDatabase
import ai.roadscan.app.data.RoadScanRepository
import ai.roadscan.app.settings.SettingsStore
import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.estimate.PriceCatalog
import androidx.room.Room

class RoadScanApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {

    init {
        // Восстановление из резервной копии подменяет файлы данных.
        // Только здесь, до SettingsStore/Room: после открытия подменить
        // их в работающем процессе уже нельзя.
        ai.roadscan.app.data.BackupManager.applyPendingRestore(application)
    }

    /** Калибровка, реквизиты документов и лицензия — всё офлайн. */
    val settings = SettingsStore(application)

    val config: AgentConfig = AgentConfig.DEFAULT

    val database: RoadScanDatabase = Room.databaseBuilder(
        application,
        RoadScanDatabase::class.java,
        "roadscan.db",
    )
        .addMigrations(
            RoadScanDatabase.MIGRATION_1_2,
            RoadScanDatabase.MIGRATION_2_3,
            RoadScanDatabase.MIGRATION_3_4,
            RoadScanDatabase.MIGRATION_4_5,
        )
        .fallbackToDestructiveMigration()
        .build()

    val repository = RoadScanRepository(database.dao())

    /** Каталог по умолчанию (ассет поставки). */
    private val baseCatalog: PriceCatalog = runCatching {
        application.assets.open("price_catalog.json").bufferedReader().use { it.readText() }
            .let(PriceCatalog::fromJson)
    }.getOrDefault(PriceCatalog.DEFAULT)

    /**
     * Действующий каталог цен: ассет + правки оператора (свои расценки
     * по договору, хранятся в настройках). var — правка обновляется
     * на месте, новые отчёты считают сразу по новым ценам.
     */
    var priceCatalog: PriceCatalog = baseCatalog.withOverrides(settings.priceOverrides())
        private set

    /** Сохраняет правки цен: в настройках и в действующем каталоге. */
    fun updatePrices(overrides: Map<String, Double>) {
        settings.savePriceOverrides(overrides)
        priceCatalog = baseCatalog.withOverrides(overrides)
    }
}
