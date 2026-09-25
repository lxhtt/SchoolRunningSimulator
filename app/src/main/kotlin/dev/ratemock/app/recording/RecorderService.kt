package dev.ratemock.app.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.SystemClock
import dev.ratemock.app.R
import dev.ratemock.core.truth.CadenceEstimator
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant

/** Screen-off foreground recorder for GPS and cumulative step-counter samples. */
class RecorderService : Service(), LocationListener, SensorEventListener {
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var writer: BufferedWriter? = null
    private var latestStepCounter: Float? = null
    private var latestLocation: Location? = null
    private var recordingFile: File? = null
    private var recordingStartedAtMs: Long = 0L
    private var targetCadenceSpm = 170.0
    private val cadenceEstimator = CadenceEstimator()
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val callbackThread = HandlerThread("RecorderCallbacks")
    private lateinit var callbackHandler: Handler
    private val writerLock = Any()
    private val sampleTicker = object : Runnable {
        override fun run() {
            writeSample()
            if (writer != null) callbackHandler.postDelayed(this, LOCATION_INTERVAL_MS)
        }
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_HEARTBEAT_MS, System.currentTimeMillis())
                .apply()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)
        createNotificationChannel()
        callbackThread.start()
        callbackHandler = Handler(callbackThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) startRecording(intent)
        return START_STICKY
    }

    private fun startRecording(intent: Intent? = null) {
        if (writer != null) return
        if (!hasLocationPermission()) return
        targetCadenceSpm = intent?.getDoubleExtra(EXTRA_TARGET_CADENCE_SPM, 170.0)?.takeIf { it.isFinite() } ?: 170.0
        cadenceEstimator.reset()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putFloat(KEY_TARGET_CADENCE_SPM, targetCadenceSpm.toFloat())
            .putFloat(KEY_CADENCE_SPM, 0f)
            .putBoolean(KEY_CADENCE_AVAILABLE, false)
            .putLong(KEY_LAST_STEP_NS, 0L)
            .putLong(KEY_LOCATION_FIX_NS, 0L)
            .putString(KEY_CADENCE_SOURCE, "unavailable")
            .putString(KEY_LOCATION_QUALITY, "waiting")
            .apply()
        val notification = notification("Recording GPS and step counter")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val directory = File(filesDir, "recordings").apply { mkdirs() }
        recordingFile = File(directory, "recording-${System.currentTimeMillis()}.csv")
        recordingStartedAtMs = System.currentTimeMillis()
        writer = BufferedWriter(FileWriter(recordingFile, false)).also {
            it.appendLine("timestamp_utc,elapsed_s,latitude_deg,longitude_deg,altitude_m,horizontal_accuracy_m,speed_mps,step_counter_total,location_age_s")
            it.flush()
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_HEARTBEAT_MS, System.currentTimeMillis())
            .apply()
        heartbeatHandler.post(heartbeat)
        callbackHandler.post(sampleTicker)
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor != null && hasActivityPermission()) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, callbackHandler)
        }
        val detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_CADENCE_SOURCE, if (detector == null || !hasActivityPermission()) "unavailable" else "warming_up")
            .apply()
        if (detector != null && hasActivityPermission()) {
            sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL, callbackHandler)
        }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                0f,
                this,
                callbackThread.looper,
            )
        } catch (_: SecurityException) {
            stopRecording()
        }
    }

    private fun stopRecording() {
        runCatching { locationManager.removeUpdates(this) }
        callbackHandler.removeCallbacks(sampleTicker)
        sensorManager.unregisterListener(this)
        synchronized(writerLock) {
            writer?.flush()
            writer?.close()
            writer = null
        }
        latestStepCounter = null
        latestLocation = null
        cadenceEstimator.reset()
        recordingFile = null
        recordingStartedAtMs = 0L
        heartbeatHandler.removeCallbacks(heartbeat)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_HEARTBEAT_MS, System.currentTimeMillis())
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onLocationChanged(location: Location) {
        latestLocation = Location(location)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_LOCATION_FIX_NS, location.elapsedRealtimeNanos)
            .putString(KEY_LOCATION_QUALITY, if (location.hasAccuracy() && location.accuracy <= 25f) "fresh" else "low_accuracy")
            .apply()
    }

    private fun writeSample() {
        val location = latestLocation ?: return
        val now = System.currentTimeMillis()
        val timestamp = Instant.ofEpochMilli(now).toString()
        val elapsed = (now - recordingStartedAtMs) / 1000.0
        val row = listOf(
            timestamp,
            elapsed,
            location.latitude,
            location.longitude,
            if (location.hasAltitude()) location.altitude else "",
            if (location.hasAccuracy()) location.accuracy else "",
            if (location.hasSpeed()) location.speed else "",
            latestStepCounter ?: "",
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos).coerceAtLeast(0L) / 1_000_000_000.0,
        ).joinToString(",")
        synchronized(writerLock) {
            val out = writer ?: return
            out.appendLine(row)
            out.flush()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            latestStepCounter = event.values.firstOrNull()
        } else if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            val cadence = cadenceEstimator.addStep(event.timestamp / 1_000_000_000.0)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_LAST_STEP_NS, event.timestamp)
                .putFloat(KEY_CADENCE_SPM, cadence?.toFloat() ?: 0f)
                .putBoolean(KEY_CADENCE_AVAILABLE, cadence != null && cadence.isFinite())
                .putString(KEY_CADENCE_SOURCE, if (cadence != null) "step_detector" else "warming_up")
                .apply()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRecording()
        callbackThread.quitSafely()
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasActivityPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RateMock recording", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .build()

    companion object {
        const val ACTION_START = "dev.ratemock.app.recording.START"
        const val ACTION_STOP = "dev.ratemock.app.recording.STOP"
        private const val HEARTBEAT_INTERVAL_MS = 1_000L
        private const val PREFS = "recorder_status"
        const val KEY_ACTIVE = "active"
        const val KEY_HEARTBEAT_MS = "heartbeat_ms"
        const val EXTRA_TARGET_CADENCE_SPM = "target_cadence_spm"
        const val KEY_TARGET_CADENCE_SPM = "target_cadence_spm"
        const val KEY_CADENCE_SPM = "cadence_spm"
        const val KEY_CADENCE_AVAILABLE = "cadence_available"
        const val KEY_CADENCE_SOURCE = "cadence_source"
        const val KEY_LAST_STEP_NS = "last_step_ns"
        const val KEY_LOCATION_FIX_NS = "location_fix_ns"
        const val KEY_LOCATION_QUALITY = "location_quality"
        private const val CHANNEL_ID = "ratemock-recording"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MS = 1_000L
    }
}
