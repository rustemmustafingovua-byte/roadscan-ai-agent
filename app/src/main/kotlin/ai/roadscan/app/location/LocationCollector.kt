package ai.roadscan.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import ai.roadscan.core.model.GeoPoint
import java.util.Locale
import java.util.concurrent.Executors

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

    /** Название улицы по обратному геокодированию (если доступно офлайн/онлайн). */
    @Volatile
    var street: String? = null
        private set

    /** Город/населённый пункт для шапки HUD. */
    @Volatile
    var city: String? = null
        private set

    private val geocoder = runCatching { Geocoder(context, Locale("uk", "UA")) }.getOrNull()
    private val geocoderExecutor = Executors.newSingleThreadExecutor()
    private var lastGeocodeAtMs = 0L
    private var lastGeocodedPoint: GeoPoint? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val point = GeoPoint(location.latitude, location.longitude, location.accuracy)
            latest = point
            accuracyM = location.accuracy
            speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).coerceAtLeast(0f) else 0f
            maybeGeocode(point)
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

    /**
     * Обратное геокодирование для HUD: улица и город в шапке.
     *
     * Сеть не обязательна: если Geocoder офлайн или не отвечает, HUD просто
     * показывает координаты. Запросы throttle'ятся (раз в 10 с или при
     * смещении > 80 м), чтобы не грузить устройство и не тратить трафик.
     */
    private fun maybeGeocode(point: GeoPoint) {
        val geocoder = geocoder ?: return
        val now = System.currentTimeMillis()
        val moved = lastGeocodedPoint?.let { previous ->
            ai.roadscan.core.geo.GeoMath.distanceMeters(previous, point) > 80.0
        } ?: true
        if (!moved && now - lastGeocodeAtMs < 10_000) return
        if (now - lastGeocodeAtMs < 2_000) return
        lastGeocodeAtMs = now
        lastGeocodedPoint = point

        runCatching {
            geocoderExecutor.execute {
                runCatching {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(point.lat, point.lon, 1)
                    val address = addresses?.firstOrNull() ?: return@execute
                    street = listOfNotNull(
                        address.thoroughfare ?: address.featureName,
                        address.subThoroughfare,
                    ).joinToString(", ").takeIf { it.isNotBlank() }
                    city = address.locality
                        ?: address.subAdminArea
                        ?: address.adminArea
                }
            }
        }
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
