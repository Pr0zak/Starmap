package com.starmap.app.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observer location via the platform [LocationManager] (no Google Play Services
 * dependency, so the app stays small and works on de-Googled devices). Falls back
 * to the last known fix and accepts a manual override from settings.
 */
class LocationProvider(context: Context) : LocationListener {

    data class Fix(val latitude: Double, val longitude: Double, val altitude: Double, val fromGps: Boolean)

    private val appContext = context.applicationContext
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<Fix?>(null)
    val location: StateFlow<Fix?> = _location

    /** True once we have permission and have started receiving updates. */
    var active = false
        private set

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (active) return
        // Don't touch LocationManager without permission — it throws SecurityException.
        // The caller re-invokes start() once the user grants the permission.
        if (!hasPermission()) return
        try {
            active = true
            // Seed with the most recent fix from any provider.
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var best: Location? = null
            for (p in providers) {
                if (!locationManager.isProviderEnabled(p)) continue
                val last = locationManager.getLastKnownLocation(p) ?: continue
                if (best == null || last.time > best!!.time) best = last
            }
            best?.let { publish(it) }
            for (p in providers) {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, 30_000L, 500f, this)
                }
            }
        } catch (e: SecurityException) {
            // Permission was revoked between the check and the call.
            active = false
        }
    }

    fun stop() {
        if (!active) return
        active = false
        locationManager.removeUpdates(this)
    }

    /** Apply a user-entered location; disables sensor updates while in manual mode. */
    fun setManual(latitude: Double, longitude: Double, altitude: Double = 0.0) {
        stop()
        _location.value = Fix(latitude, longitude, altitude, fromGps = false)
    }

    override fun onLocationChanged(location: Location) = publish(location)

    private fun publish(l: Location) {
        _location.value = Fix(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else 0.0, fromGps = true)
    }

    @Deprecated("Required by older LocationListener contract")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
