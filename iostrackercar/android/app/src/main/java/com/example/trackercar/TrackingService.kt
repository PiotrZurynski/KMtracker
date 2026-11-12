package com.example.trackercar

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat

/** Foreground service: GPS włączony tylko między Start a Stop. */
class TrackingService : Service(), LocationListener {
    private var last: Location? = null
    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val idle = Runnable { notifyIdle() }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopAction(): Notification.Action {
        val pi = PendingIntent.getService(this, 0,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", pi).build()
    }

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
            .addAction(stopAction())
            .build()
        ServiceCompat.startForeground(this, 1, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)

        Tracker.init(this)
        if (Tracker.tracking.value) return START_NOT_STICKY   // powtórny Start – już liczymy
        Tracker.begin()
        last = null
        resetIdle()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 15f, this)
        } catch (e: SecurityException) {      // cofnięta zgoda
            stopTracking()
        } catch (e: IllegalArgumentException) { // brak dostawcy GPS
            stopTracking()
        }
        return START_NOT_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        // te same filtry co na iOS: słaba dokładność, stare pomiary, szum na postoju, skoki
        if (!loc.hasAccuracy() || loc.accuracy > 50) return
        val age = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1e9
        if (age > 10) return
        val prev = last ?: run { last = loc; Tracker.addPoint(loc.latitude, loc.longitude); return }
        val delta = prev.distanceTo(loc).toDouble()
        val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
        if (delta < maxOf(8.0, loc.accuracy * 0.5) || dt <= 0 || delta / dt > 70) return
        last = loc
        Tracker.add(delta)
        Tracker.addPoint(loc.latitude, loc.longitude)
        resetIdle()
    }

    // Przypomnienie „zapomniałeś kliknąć Stop": timer w już działającym serwisie, bez dodatkowego GPS-a.
    private fun resetIdle() {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, 10 * 60 * 1000L)
    }

    private fun notifyIdle() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("idle", "Przypomnienia", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(this, "idle")
            .setContentTitle("TrackerCar")
            .setContentText("Auto stoi od 10 minut. Zatrzymać przejazd?")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setAutoCancel(true)
            .addAction(stopAction())
            .build()
        nm.notify(2, n)
    }

    private fun stopTracking() {
        handler.removeCallbacks(idle)
        getSystemService(NotificationManager::class.java).cancel(2)
        if (::lm.isInitialized) lm.removeUpdates(this)
        Tracker.finish()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(idle)
        if (::lm.isInitialized) lm.removeUpdates(this)
        super.onDestroy()
    }

    companion object { const val ACTION_STOP = "STOP" }
}
