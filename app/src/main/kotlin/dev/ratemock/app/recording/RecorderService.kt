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
import android.os.IBinder
import android.os.Looper
import dev.ratemock.app.R
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
    private var recordingFile: File? = null
    private var recordingStartedAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) startRecording()
        return START_STICKY
    }

    private fun startRecording() {
        if (writer != null) return
        if (!hasLocationPermission()) return
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
            it.appendLine("timestamp_utc,elapsed_s,latitude_deg,longitude_deg,altitude_m,horizontal_accuracy_m,speed_mps,step_counter_total")
            it.flush()
        }
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor != null && hasActivityPermission()) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                0f,
                this,
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
            stopRecording()
        }
    }

    private fun stopRecording() {
        runCatching { locationManager.removeUpdates(this) }
        sensorManager.unregisterListener(this)
        writer?.flush()
        writer?.close()
        writer = null
        latestStepCounter = null
        recordingFile = null
        recordingStartedAtMs = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onLocationChanged(location: Location) {
        val out = writer ?: return
        val now = System.currentTimeMillis()
        val timestamp = Instant.ofEpochMilli(now).toString()
        val elapsed = (now - recordingStartedAtMs) / 1000.0
        out.appendLine(
            listOf(
                timestamp,
                elapsed,
                location.latitude,
                location.longitude,
                if (location.hasAltitude()) location.altitude else "",
                if (location.hasAccuracy()) location.accuracy else "",
                if (location.hasSpeed()) location.speed else "",
                latestStepCounter ?: "",
            ).joinToString(","),
        )
        out.flush()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) latestStepCounter = event.values.firstOrNull()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRecording()
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
        private const val CHANNEL_ID = "ratemock-recording"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MS = 1_000L
    }
}
