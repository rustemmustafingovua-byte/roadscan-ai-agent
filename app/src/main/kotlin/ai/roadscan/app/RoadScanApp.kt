package ai.roadscan.app

import android.app.Application
import ai.roadscan.app.data.RoadScanDatabase
import ai.roadscan.app.data.RoadScanRepository
import ai.roadscan.app.settings.SettingsStore
import ai.roadscan.core.agent.AgentConfig
import ai.roadscan.core.estimate.PriceCatalog
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.room.Room

class RoadScanApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        configureCameraX()
        container = AppContainer(this)
    }

    /**
     * Работаем только с задней камерой: не трогаем фронтальный объектив
     * (на части устройств с фронтальной камерой это даёт свечение LED) и
     * убираем предупреждения валидатора на устройствах с одной камерой.
     *
     * `configureInstance` допускает один вызов на процесс — здесь он и есть.
     */
    private fun configureCameraX() {
        runCatching {
            ProcessCameraProvider.configureInstance(
                CameraXConfig.Builder()
                    .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
                    .build(),
            )
        }
    }
}

class AppContainer(application: Application) {

    /** Калибровка, реквизиты документов и лицензия — всё офлайн. */
    val settings = SettingsStore(application)

    val config: AgentConfig = AgentConfig.DEFAULT

    val database: RoadScanDatabase = Room.databaseBuilder(
        application,
        RoadScanDatabase::class.java,
        "roadscan.db",
    )
        .addMigrations(RoadScanDatabase.MIGRATION_1_2)
        .fallbackToDestructiveMigration()
        .build()

    val repository = RoadScanRepository(database.dao())

    val priceCatalog: PriceCatalog = runCatching {
        application.assets.open("price_catalog.json").bufferedReader().use { it.readText() }
            .let(PriceCatalog::fromJson)
    }.getOrDefault(PriceCatalog.DEFAULT)
}
