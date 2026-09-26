package dev.ratemock.app.simulation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import dev.ratemock.app.MainActivity
import dev.ratemock.app.R
import dev.ratemock.core.truth.InteractiveSimulation
import dev.ratemock.core.truth.SimulationSnapshot
import dev.ratemock.core.truth.SimulationStatus
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

/** A user-initiated, time-bounded simulation. It never reads or injects device location. */
class SimulatorService : Service() {
    private val worker = HandlerThread("RateMockSimulator")
    private lateinit var handler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock
    private var session: InteractiveSimulation? = null
    private var historyWriter: BufferedWriter? = null
    private var historyWritten = 0
    private var started = false
    private var lastTickMs = 0L
    private var lastNotificationMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            advanceToNow()
            publish()
            if (session?.snapshot()?.status in ACTIVE) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker.start()
        handler = Handler(worker.looper)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dev.ratemock.app:simulation")
        wakeLock.setReferenceCounted(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.sim_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != ACTION_START && !started) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STATUS, if (action == ACTION_STOP) SimulationStatus.STOPPED.name else STATUS_INTERRUPTED)
                .apply()
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START -> {
                if (started) return START_NOT_STICKY
                started = true
                try {
                    val notification = notification(getString(R.string.sim_starting), false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (error: RuntimeException) {
                    failure(error)
                    return START_NOT_STICKY
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_HISTORY_FILE)
                    .putBoolean(KEY_HISTORY_CLOSED, false).remove(KEY_ERROR).apply()
                val speed = intent?.getDoubleExtra(EXTRA_SPEED_MPS, Double.NaN) ?: Double.NaN
                val duration = intent?.getDoubleExtra(EXTRA_DURATION_SECONDS, Double.NaN) ?: Double.NaN
                val cadence = intent?.getDoubleExtra(EXTRA_MANUAL_CADENCE_SPM, Double.NaN)
                    ?.takeIf { it.isFinite() }
                val slowdown = intent?.getDoubleExtra(EXTRA_FATIGUE_REDUCTION, 0.0) ?: 0.0
                handler.post {
                    try {
                        session = InteractiveSimulation(speed, duration, cadence, slowdown)
                        val directory = File(filesDir, "simulations").apply { mkdirs() }
                        val file = File(directory, "simulation-${System.currentTimeMillis()}.csv")
                        historyWriter = BufferedWriter(FileWriter(file)).also {
                            it.appendLine("# provenance=simulation;calibration=uncalibrated")
                            it.appendLine("elapsed_s,distance_m,speed_mps,cadence_spm,east_m,north_m")
                            it.flush()
                        }
                        historyWritten = 0
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_HISTORY_FILE, file.name).remove(KEY_ERROR).apply()
                        lastTickMs = SystemClock.elapsedRealtime()
                        publish(forceNotification = true)
                        handler.postDelayed(ticker, TICK_MS)
                    } catch (error: Exception) {
                        failure(error)
                    }
                }
            }
            ACTION_SET_SPEED -> handler.post {
                val current = session ?: return@post
                advanceToNow()
                try {
                    current.setTargetSpeed(intent?.getDoubleExtra(EXTRA_SPEED_MPS, Double.NaN) ?: Double.NaN)
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_ERROR).apply()
                } catch (error: IllegalArgumentException) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ERROR, ERROR_INFEASIBLE).apply()
                }
                publish(forceNotification = true)
            }
            ACTION_PAUSE, ACTION_RESUME, ACTION_STOP -> handler.post {
                val current = session ?: return@post
                advanceToNow()
                when (action) {
                    ACTION_PAUSE -> current.pause()
                    ACTION_RESUME -> current.resume()
                    ACTION_STOP -> current.stop()
                }
                lastTickMs = SystemClock.elapsedRealtime()
                publish(forceNotification = true)
            }
        }
        return START_NOT_STICKY
    }

    private fun advanceToNow() {
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastTickMs).coerceAtLeast(0L) / 1_000.0
        lastTickMs = now
        session?.advanceBy(delta)
    }

    private fun publish(forceNotification: Boolean = false) {
        val snapshot = session?.snapshot() ?: return
        writeHistory()
        if (snapshot.status == SimulationStatus.RUNNING && !wakeLock.isHeld) {
            val remainingMs = ((snapshot.durationSeconds - snapshot.elapsedSeconds).coerceAtLeast(0.0) * 1_000).toLong()
            wakeLock.acquire(remainingMs + WAKE_LOCK_MARGIN_MS)
        } else if (snapshot.status != SimulationStatus.RUNNING) {
            releaseWakeLock()
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATUS, snapshot.status.name)
            .putString(KEY_MODE, snapshot.mode.name)
            .putLong(KEY_HEARTBEAT_MS, System.currentTimeMillis())
            .putFloat(KEY_TARGET_MPS, snapshot.targetSpeedMps.toFloat())
            .putFloat(KEY_EFFECTIVE_TARGET_MPS, snapshot.effectiveTargetSpeedMps.toFloat())
            .putFloat(KEY_MANUAL_CADENCE_SPM, snapshot.manualCadenceSpm?.toFloat() ?: 0f)
            .putFloat(KEY_FATIGUE_REDUCTION, snapshot.fatigueReduction.toFloat())
            .putFloat(KEY_DURATION_SECONDS, snapshot.durationSeconds.toFloat())
            .putFloat(KEY_ELAPSED_SECONDS, snapshot.elapsedSeconds.toFloat())
            .putFloat(KEY_DISTANCE_METERS, snapshot.distanceMeters.toFloat())
            .putFloat(KEY_SPEED_MPS, snapshot.speedMps.toFloat())
            .putFloat(KEY_CADENCE_SPM, snapshot.cadenceSpm.toFloat())
            .putLong(KEY_STEPS, snapshot.steps)
            .apply()
        if (snapshot.status !in ACTIVE) {
            closeHistory(success = true)
            handler.removeCallbacks(ticker)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            val now = SystemClock.elapsedRealtime()
            if (forceNotification || now - lastNotificationMs >= NOTIFICATION_INTERVAL_MS) {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID, notification(progressText(snapshot), snapshot.status == SimulationStatus.PAUSED, snapshot),
                )
                lastNotificationMs = now
            }
        }
    }

    private fun writeHistory() {
        val samples = session?.history() ?: return
        val out = historyWriter ?: return
        try {
            for (index in historyWritten until samples.size) {
                val point = samples[index]
                out.appendLine(listOf(point.timeSeconds, point.distanceMeters, point.speedMps,
                    point.cadenceSpm, point.eastMeters, point.northMeters).joinToString(","))
            }
            out.flush()
            historyWritten = samples.size
        } catch (_: IOException) {
            closeHistory()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ERROR, ERROR_HISTORY_WRITE).apply()
        }
    }

    private fun closeHistory(success: Boolean = false) {
        val writer = historyWriter ?: return
        historyWriter = null
        val closed = runCatching { writer.close() }.isSuccess
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_HISTORY_CLOSED, closed && success).apply()
    }

    private fun progressText(snapshot: SimulationSnapshot): String {
        val label = if (snapshot.status == SimulationStatus.PAUSED) {
            getString(R.string.sim_status_paused)
        } else {
            getString(R.string.sim_status_running)
        }
        val elapsed = snapshot.elapsedSeconds.toInt()
        val total = snapshot.durationSeconds.toInt()
        return getString(R.string.sim_notification_progress, label, elapsed / 60, elapsed % 60,
            total / 60, total % 60, snapshot.distanceMeters)
    }

    private fun notification(text: String, paused: Boolean, snapshot: SimulationSnapshot? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = if (paused) ACTION_RESUME else ACTION_PAUSE
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sim_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_simulation_notification)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (snapshot != null) {
            builder.setProgress(snapshot.durationSeconds.toInt(), snapshot.elapsedSeconds.toInt().coerceAtMost(snapshot.durationSeconds.toInt()), false)
                .setSubText(getString(R.string.sim_cadence_value, snapshot.cadenceSpm))
        }
        return builder
            .addAction(0, getString(if (paused) R.string.sim_resume else R.string.sim_pause), action(toggle, 2))
            .addAction(0, getString(R.string.sim_stop), action(ACTION_STOP, 3))
            .build()
    }

    private fun action(command: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, SimulatorService::class.java).setAction(command),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun failure(error: Exception) {
        closeHistory(success = false)
        releaseWakeLock()
        session = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATUS, STATUS_ERROR)
            .putString(KEY_ERROR, error.javaClass.simpleName)
            .putLong(KEY_HEARTBEAT_MS, System.currentTimeMillis())
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.post {
            closeHistory(success = false)
            releaseWakeLock()
            if (session?.snapshot()?.status in ACTIVE) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATUS, STATUS_INTERRUPTED).apply()
            }
            worker.quitSafely()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.ratemock.app.simulation.START"
        const val ACTION_PAUSE = "dev.ratemock.app.simulation.PAUSE"
        const val ACTION_RESUME = "dev.ratemock.app.simulation.RESUME"
        const val ACTION_STOP = "dev.ratemock.app.simulation.STOP"
        const val ACTION_SET_SPEED = "dev.ratemock.app.simulation.SET_SPEED"
        const val EXTRA_SPEED_MPS = "speed_mps"
        const val EXTRA_MANUAL_CADENCE_SPM = "manual_cadence_spm"
        const val EXTRA_FATIGUE_REDUCTION = "fatigue_reduction"
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        const val PREFS = "simulator_status"
        const val KEY_STATUS = "status"
        const val KEY_MODE = "mode"
        const val KEY_HEARTBEAT_MS = "heartbeat_ms"
        const val KEY_TARGET_MPS = "target_mps"
        const val KEY_EFFECTIVE_TARGET_MPS = "effective_target_mps"
        const val KEY_MANUAL_CADENCE_SPM = "manual_cadence_spm"
        const val KEY_FATIGUE_REDUCTION = "fatigue_reduction"
        const val KEY_DURATION_SECONDS = "duration_seconds"
        const val KEY_ELAPSED_SECONDS = "elapsed_seconds"
        const val KEY_DISTANCE_METERS = "distance_meters"
        const val KEY_SPEED_MPS = "speed_mps"
        const val KEY_CADENCE_SPM = "cadence_spm"
        const val KEY_STEPS = "steps"
        const val KEY_HISTORY_FILE = "history_file"
        const val KEY_HISTORY_CLOSED = "history_closed"
        const val ERROR_HISTORY_WRITE = "HISTORY_WRITE"
        const val KEY_ERROR = "error"
        const val ERROR_INFEASIBLE = "INFEASIBLE"
        const val STATUS_INTERRUPTED = "INTERRUPTED"
        const val STATUS_ERROR = "ERROR"
        private const val CHANNEL_ID = "ratemock-simulation"
        private const val NOTIFICATION_ID = 1002
        private const val TICK_MS = 1_000L
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private const val WAKE_LOCK_MARGIN_MS = 60_000L
        private val ACTIVE = setOf(SimulationStatus.RUNNING, SimulationStatus.PAUSED)
    }
}
