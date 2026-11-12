package com.example.trackercar

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat

/** Foreground service: GPS włączony tylko między Start a Stop. */
class TrackingService : Service(), LocationListener {
    private var last: Location? = null
    private lateinit var lm: LocationManager

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopTracking(); return START_NOT_STICKY }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("trip", "Przejazd", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "trip")
            .setContentTitle("TrackerCar")
            .setContentText("Liczę kilometry")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)

        Tracker.init(this)
        Tracker.begin()
        last = null
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 15f, this)
        return START_NOT_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        // te same filtry co na iOS: słaba dokładność, stare pomiary, szum na postoju, skoki
        if (!loc.hasAccuracy() || loc.accuracy > 50) return
        val age = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1e9
        if (age > 10) return
        val prev = last ?: run { last = loc; return }
        val delta = prev.distanceTo(loc).toDouble()
        val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
        if (delta < maxOf(8.0, loc.accuracy * 0.5) || dt <= 0 || delta / dt > 70) return
        last = loc
        Tracker.add(delta)
    }

    private fun stopTracking() {
        if (::lm.isInitialized) lm.removeUpdates(this)
        Tracker.finish()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (::lm.isInitialized) lm.removeUpdates(this)
        super.onDestroy()
    }

    companion object { const val ACTION_STOP = "STOP" }
}
