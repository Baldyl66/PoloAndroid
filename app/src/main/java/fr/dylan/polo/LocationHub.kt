package fr.dylan.polo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import org.json.JSONObject

/**
 * Position lue directement sur le GPS (et le réseau) d'Android, sans passer par les services Google.
 * Indispensable sur les autoradios non certifiés, où la géolocalisation du WebView échoue
 * et où l'appli restait bloquée sur la position par défaut.
 */
object LocationHub {
    private var started = false
    private var lastGpsAt = 0L
    private var emit: ((String, String) -> Unit)? = null
    @Volatile var last: String? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = push(l)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("API < 29") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun init(emitter: (String, String) -> Unit) { emit = emitter }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (started) return
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { p -> try { lm.allProviders.contains(p) && (p != LocationManager.GPS_PROVIDER || fine) } catch (e: Exception) { false } }
        if (providers.isEmpty()) return
        started = true
        // dernière position connue tout de suite, pour ne pas afficher la position par défaut
        providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }?.let { push(it, fresh = false) }
        for (p in providers) {
            try { lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper()) } catch (e: Exception) {}
        }
    }

    private fun push(l: Location, fresh: Boolean = true) {
        val gps = l.provider == LocationManager.GPS_PROVIDER
        val now = System.currentTimeMillis()
        if (gps && fresh) lastGpsAt = now
        // le réseau ne doit pas écraser un GPS récent (moins précis)
        else if (!gps && now - lastGpsAt < 10_000) return
        val o = JSONObject()
            .put("lat", l.latitude).put("lon", l.longitude)
            .put("acc", if (l.hasAccuracy()) l.accuracy.toDouble() else 50.0)
            .put("spd", if (l.hasSpeed()) l.speed.toDouble() else JSONObject.NULL)
            .put("hd", if (l.hasBearing() && l.hasSpeed() && l.speed > 1f) l.bearing.toDouble() else JSONObject.NULL)
            .put("alt", if (l.hasAltitude()) l.altitude else JSONObject.NULL)
            .put("t", if (fresh) now else l.time)
        last = o.toString()
        emit?.invoke("loc", last!!)
    }
}
