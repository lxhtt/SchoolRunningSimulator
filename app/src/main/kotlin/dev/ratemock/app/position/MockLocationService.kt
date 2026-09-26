package dev.ratemock.app.position

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dev.ratemock.app.MainActivity
import dev.ratemock.app.R
import dev.ratemock.app.recording.RecorderService
import dev.ratemock.app.simulation.SimulatorService
import dev.ratemock.core.position.PositionPoint
import dev.ratemock.core.position.SimulationRoute
import java.io.File

/** User-controlled mock GPS. Never consumes real recorder data or generates step events. */
class MockLocationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: LocationManager
    private var active = false
    private var paused = false
    private var registered = false
    private var loadingRoute = false
    private var loadGeneration = 0
    private var point: PositionPoint? = null
    private var routeOrigin: PositionPoint? = null
    private var route: List<SimulationRoute.Sample>? = null
    private var routeIndex = 0
    private var routeStartedElapsedMs = 0L
    private var pauseBeganElapsedMs = 0L
    private var routeWakeLock: PowerManager.WakeLock? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            if (!paused) {
                try {
                    val completed = advanceRoute()
                    publish()
                    if (completed) { shutdown("STOPPED"); return }
                } catch (error: Exception) { fail(error); return }
            } else status("PAUSED")
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(LocationManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "模拟定位", NotificationManager.IMPORTANCE_LOW),
        )
        val previous = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATUS, "IDLE")
        if (previous == "RUNNING" || previous == "PAUSED" || previous == "LOADING") {
            status("INTERRUPTED")
            runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_ROUTE_START -> {
                if (active || loadingRoute) return START_NOT_STICKY
                try {
                    val selected = PositionPoint(intent.getDoubleExtra(LAT, Double.NaN),
                        intent.getDoubleExtra(LON, Double.NaN), intent.getDoubleExtra(ALT, 0.0))
                    startProvider()
                    point = selected
                    if (intent.action == ACTION_ROUTE_START) loadRoute(selected)
                    else {
                        active = true
                        status("RUNNING")
                        handler.post(ticker)
                    }
                } catch (error: Exception) { fail(error) }
            }
            ACTION_UPDATE -> if (active) {
                try {
                    point = PositionPoint(intent.getDoubleExtra(LAT, Double.NaN),
                        intent.getDoubleExtra(LON, Double.NaN), intent.getDoubleExtra(ALT, 0.0))
                    route = null
                    routeOrigin = null
                    if (!paused) publish()
                } catch (error: Exception) { fail(error) }
            } else shutdown("INTERRUPTED")
            ACTION_PAUSE -> if (active) {
                paused = true
                pauseBeganElapsedMs = SystemClock.elapsedRealtime()
                releaseRouteWakeLock()
                status("PAUSED")
            } else if (!loadingRoute) shutdown("INTERRUPTED")
            ACTION_RESUME -> if (active) {
                if (paused && route != null) routeStartedElapsedMs += SystemClock.elapsedRealtime() - pauseBeganElapsedMs
                paused = false
                acquireRouteWakeLock()
                status("RUNNING")
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            } else if (!loadingRoute) shutdown("INTERRUPTED")
            ACTION_STOP -> shutdown("STOPPED")
            else -> shutdown("INTERRUPTED")
        }
        return START_NOT_STICKY
    }

    private fun startProvider() {
        check(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "需要精确定位权限"
        }
        check(!getSharedPreferences(RecorderService.PREFS, MODE_PRIVATE).getBoolean(RecorderService.KEY_ACTIVE, false)) {
            "实跑记录正在运行，请先停止记录"
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIFICATION_ID, notification())
        runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false,
            true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
        registered = true
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    private fun loadRoute(origin: PositionPoint) {
        val prefs = getSharedPreferences(SimulatorService.PREFS, MODE_PRIVATE)
        check(prefs.getString(SimulatorService.KEY_STATUS, "IDLE") in setOf("STOPPED", "COMPLETED") &&
            prefs.getBoolean(SimulatorService.KEY_HISTORY_CLOSED, false) &&
            prefs.getString(SimulatorService.KEY_ERROR, null) == null) {
            "只能回放已停止、成功关闭的模拟历史"
        }
        val fileName = prefs.getString(SimulatorService.KEY_HISTORY_FILE, null)
        check(fileName != null && fileName.matches(Regex("simulation-[0-9]+\\.csv"))) { "模拟历史文件名无效" }
        val file = File(File(filesDir, "simulations"), fileName)
        check(file.isFile && file.length() in 1..1_048_576L) { "模拟历史文件不存在或过大" }
        loadingRoute = true
        val generation = ++loadGeneration
        status("LOADING")
        Thread {
            val result = runCatching { file.useLines { SimulationRoute.parse(it) } }
            handler.post {
                if (!loadingRoute || generation != loadGeneration) return@post
                result.onSuccess { samples ->
                    route = samples
                    routeOrigin = origin
                    routeIndex = 0
                    routeStartedElapsedMs = SystemClock.elapsedRealtime()
                    loadingRoute = false
                    active = true
                    paused = false
                    acquireRouteWakeLock()
                    status("RUNNING")
                    handler.post(ticker)
                }.onFailure { fail(it) }
            }
        }.start()
    }

    /** Returns true after publishing the final sample. Manual mode never finishes automatically. */
    private fun advanceRoute(): Boolean {
        val samples = route ?: return false
        val elapsed = (SystemClock.elapsedRealtime() - routeStartedElapsedMs) / 1_000.0
        while (routeIndex + 1 < samples.size && samples[routeIndex + 1].elapsedSeconds <= elapsed) routeIndex++
        val sample = samples[routeIndex]
        val origin = checkNotNull(routeOrigin)
        val distance = kotlin.math.hypot(sample.eastMeters, sample.northMeters)
        val segments = kotlin.math.ceil(distance / 9_000.0).toInt().coerceAtLeast(1)
        var current = origin
        repeat(segments) { current = current.moved(sample.eastMeters / segments, sample.northMeters / segments) }
        point = current
        return elapsed >= samples.last().elapsedSeconds
    }

    private fun publish() {
        val selected = checkNotNull(point)
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = selected.latitude
            longitude = selected.longitude
            altitude = selected.altitude
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
        status("RUNNING")
    }

    private fun status(value: String, error: String? = null) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATUS, value).putString(KEY_ERROR, error)
            .putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()
    }

    private fun acquireRouteWakeLock() {
        val samples = route ?: return
        val remainingMs = ((samples.last().elapsedSeconds * 1_000.0) -
            (SystemClock.elapsedRealtime() - routeStartedElapsedMs)).toLong().coerceAtLeast(1_000L)
        val lock = routeWakeLock ?: getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "dev.ratemock.app:mock-route"
        ).also {
            it.setReferenceCounted(false)
            routeWakeLock = it
        }
        if (!lock.isHeld) lock.acquire(remainingMs + WAKE_LOCK_MARGIN_MS)
    }

    private fun releaseRouteWakeLock() {
        routeWakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
    }

    private fun cleanup() {
        releaseRouteWakeLock()
        handler.removeCallbacks(ticker)
        loadingRoute = false
        loadGeneration++
        active = false
        route = null
        routeOrigin = null
        if (registered) {
            runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            registered = false
        }
    }

    private fun fail(error: Throwable) {
        cleanup()
        status("ERROR", when (error) {
            is SecurityException -> "请在开发者选项将 RateMock 设为模拟位置信息应用"
            else -> error.message?.take(160) ?: error.javaClass.simpleName
        })
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdown(value: String) {
        cleanup()
        status(value)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 9, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_simulation_notification)
            .setContentTitle("RateMock · 模拟定位中").setContentText("仅发送用户选定的位置")
            .setContentIntent(open).setOngoing(true).addAction(0, "停止", stop).build()
    }

    override fun onDestroy() {
        cleanup()
        val stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATUS, "IDLE")
        if (stored in setOf("RUNNING", "PAUSED", "LOADING")) status("INTERRUPTED")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.ratemock.app.position.START"
        const val ACTION_ROUTE_START = "dev.ratemock.app.position.ROUTE_START"
        const val ACTION_UPDATE = "dev.ratemock.app.position.UPDATE"
        const val ACTION_PAUSE = "dev.ratemock.app.position.PAUSE"
        const val ACTION_RESUME = "dev.ratemock.app.position.RESUME"
        const val ACTION_STOP = "dev.ratemock.app.position.STOP"
        const val LAT = "latitude"
        const val LON = "longitude"
        const val ALT = "altitude"
        const val PREFS = "mock_location_status"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
        const val KEY_HEARTBEAT = "heartbeat"
        private const val CHANNEL = "ratemock-mock-location"
        private const val WAKE_LOCK_MARGIN_MS = 60_000L
    }
}
