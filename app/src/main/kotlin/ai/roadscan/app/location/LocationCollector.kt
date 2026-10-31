package ai.roadscan.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import ai.roadscan.core.model.GeoPoint

/**
 * GNSS без Google Play Services: GPS_PROVIDER + NETWORK_PROVIDER.
 * RTK-модуль подключается как внешний источник и просто перекрывает точку.
 */
class LocationCollector(private val context: Context) {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    var latest: GeoPoint? = null
        private set

    @Volatile
    var accuracyM: Float = Float.NaN
        private set

    @Volatile
    var speedKmh: Float = 0f
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latest = GeoPoint(location.latitude, location.longitude, location.accuracy)
            accuracyM = location.accuracy
            speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).coerceAtLeast(0f) else 0f
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) return
        runCatching {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    GPS_INTERVAL_MS,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    NETWORK_INTERVAL_MS,
                    5f,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                listener.onLocationChanged(it)
            }
        }
    }

    fun stop() {
        runCatching { manager.removeUpdates(listener) }
    }

    /** Ручная подстановка точки от внешнего RTK-приёмника. */
    fun injectRtk(latitude: Double, longitude: Double, accuracyM: Float, speedKmh: Float = this.speedKmh) {
        latest = GeoPoint(latitude, longitude, accuracyM)
        this.accuracyM = accuracyM
        this.speedKmh = speedKmh
    }

    private companion object {
        const val GPS_INTERVAL_MS = 200L
        const val NETWORK_INTERVAL_MS = 1_000L
    }
}
