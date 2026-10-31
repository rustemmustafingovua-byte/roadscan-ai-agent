package ai.roadscan.app

import android.app.Application
import ai.roadscan.app.data.RoadScanDatabase
import ai.roadscan.app.data.RoadScanRepository
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
    val config = AgentConfig.DEFAULT

    val database: RoadScanDatabase = Room.databaseBuilder(
        application,
        RoadScanDatabase::class.java,
        "roadscan.db",
    ).fallbackToDestructiveMigration().build()

    val repository = RoadScanRepository(database.dao())

    val priceCatalog: PriceCatalog = runCatching {
        application.assets.open("price_catalog.json").bufferedReader().use { it.readText() }
            .let(PriceCatalog::fromJson)
    }.getOrDefault(PriceCatalog.DEFAULT)
}
