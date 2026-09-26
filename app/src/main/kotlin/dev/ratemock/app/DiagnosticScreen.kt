package dev.ratemock.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ratemock.core.diagnostics.DiagnosticSensor
import dev.ratemock.core.diagnostics.SensorDiagnosticEvent
import dev.ratemock.core.diagnostics.SensorDiagnosticReport
import dev.ratemock.core.diagnostics.SensorDiagnosticSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_DIAGNOSTIC_EVENTS = 10_000

@Composable
fun DiagnosticScreen(onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val sensorManager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val capabilities = remember(sensorManager) {
        buildSet {
            if (sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null) add(DiagnosticSensor.STEP_DETECTOR)
            if (sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null) add(DiagnosticSensor.STEP_COUNTER)
            if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) add(DiagnosticSensor.ACCELEROMETER)
        }
    }
    var permissionGranted by remember { mutableStateOf(hasActivityPermission(context)) }
    var observing by remember { mutableStateOf(false) }
    var activeListener by remember { mutableStateOf<SensorEventListener?>(null) }
    var events by remember { mutableStateOf(emptyList<SensorDiagnosticEvent>()) }
    var truncated by remember { mutableStateOf(false) }
    var registrationFailed by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    var exportSucceeded by remember { mutableStateOf(false) }
    var sessionStartNs by remember { mutableStateOf<Long?>(null) }
    var sessionEndNs by remember { mutableStateOf<Long?>(null) }
    var pendingReport by remember { mutableStateOf<String?>(null) }
    val latestEvents by rememberUpdatedState(events)
    val latestObserving by rememberUpdatedState(observing)
    val scope = rememberCoroutineScope()
    val summary = remember(capabilities, events) { SensorDiagnosticReport.summarize(capabilities, events) }
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && latestObserving) {
                    activeListener?.let(sensorManager::unregisterListener)
                    activeListener = null
                    sessionEndNs = SystemClock.elapsedRealtimeNanos()
                    observing = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    LaunchedEffect(context) {
        while (true) {
            permissionGranted = hasActivityPermission(context)
            if (!permissionGranted && observing) {
                activeListener?.let(sensorManager::unregisterListener)
                activeListener = null
                sessionEndNs = SystemClock.elapsedRealtimeNanos()
                observing = false
            }
            delay(1_000)
        }
    }
    DisposableEffect(observing, permissionGranted, sensorManager) {
        if (!observing || !permissionGranted) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (latestEvents.size >= MAX_DIAGNOSTIC_EVENTS) {
                        truncated = true
                        return
                    }
                    val kind = when (event.sensor.type) {
                        Sensor.TYPE_STEP_DETECTOR -> DiagnosticSensor.STEP_DETECTOR
                        Sensor.TYPE_STEP_COUNTER -> DiagnosticSensor.STEP_COUNTER
                        else -> return
                    }
                    events = latestEvents + SensorDiagnosticEvent(
                        kind, event.timestamp,
                        if (kind == DiagnosticSensor.STEP_COUNTER) event.values.firstOrNull()?.toDouble() else null,
                    )
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            val registered = listOf(Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_STEP_COUNTER).mapNotNull {
                sensorManager.getDefaultSensor(it)
            }.map { sensor -> runCatching {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }.getOrDefault(false) }.any { it }
            if (!registered) {
                registrationFailed = true
                observing = false
            } else {
                activeListener = listener
            }
            onDispose {
                sensorManager.unregisterListener(listener)
                if (activeListener === listener) activeListener = null
            }
        }
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val payload = pendingReport
        pendingReport = null
        if (uri != null && payload != null) scope.launch {
            exportFailed = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(payload) }
                        ?: error("Document is not writable")
                }.isFailure
            }
            exportSucceeded = !exportFailed
        }
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.diagnostic_title), modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.diagnostic_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.diagnostic_permissions,
                if (permissionGranted) stringResource(R.string.diagnostic_available) else stringResource(R.string.diagnostic_unavailable)),
                style = MaterialTheme.typography.titleSmall)
            if (!permissionGranted) Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.recorder_permissions))
            }
            val stateLabel = when {
                observing -> R.string.diagnostic_observing
                sessionStartNs != null -> R.string.diagnostic_stopped
                else -> R.string.diagnostic_idle
            }
            Text(stringResource(stateLabel), style = MaterialTheme.typography.titleMedium,
                color = if (observing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    events = emptyList()
                    truncated = false
                    registrationFailed = false
                    exportFailed = false
                    exportSucceeded = false
                    sessionStartNs = SystemClock.elapsedRealtimeNanos()
                    sessionEndNs = null
                    observing = true
                }, enabled = lifecycleOwner != null && !observing && permissionGranted && capabilities.any {
                    it == DiagnosticSensor.STEP_COUNTER || it == DiagnosticSensor.STEP_DETECTOR
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.diagnostic_start)) }
                OutlinedButton(onClick = {
                    activeListener?.let(sensorManager::unregisterListener)
                    activeListener = null
                    sessionEndNs = SystemClock.elapsedRealtimeNanos()
                    observing = false
                }, enabled = observing, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.diagnostic_stop)) }
            }
            if (registrationFailed) Text(stringResource(R.string.diagnostic_register_error), color = MaterialTheme.colorScheme.error)
            summary.forEach { item ->
                val name = when (item.sensor) {
                    DiagnosticSensor.STEP_DETECTOR -> R.string.diagnostic_detector
                    DiagnosticSensor.STEP_COUNTER -> R.string.diagnostic_counter
                    DiagnosticSensor.ACCELEROMETER -> R.string.diagnostic_accelerometer
                }
                Text(stringResource(name), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.diagnostic_sensor_summary,
                    if (item.available) stringResource(R.string.diagnostic_available) else stringResource(R.string.diagnostic_unavailable),
                    item.eventCount, item.maxGapSeconds ?: 0.0), style = MaterialTheme.typography.bodyMedium)
                if (!item.timestampsIncreasing || item.counterMonotonic == false) {
                    Text(stringResource(R.string.diagnostic_order_warning), color = MaterialTheme.colorScheme.error)
                }
            }
            if (truncated) Text(stringResource(R.string.diagnostic_truncated), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.diagnostic_limit), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                pendingReport = diagnosticJson(summary, sessionStartNs, sessionEndNs, truncated)
                exportFailed = false
                exportSucceeded = false
                createDocument.launch("ratemock-diagnostic.json")
            }, enabled = !observing && sessionStartNs != null, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diagnostic_export))
            }
            if (exportFailed) Text(stringResource(R.string.diagnostic_export_error), color = MaterialTheme.colorScheme.error)
            if (exportSucceeded) Text(stringResource(R.string.diagnostic_export_success), color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun hasActivityPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

private fun diagnosticJson(
    summary: List<SensorDiagnosticSummary>, startNs: Long?, endNs: Long?, truncated: Boolean,
): String = JSONObject().apply {
    put("provenance", "real_diagnostic")
    put("source", "RateMock foreground sensor listener")
    put("android_sdk", Build.VERSION.SDK_INT)
    put("start_elapsed_realtime_ns", startNs)
    put("end_elapsed_realtime_ns", endNs)
    put("truncated", truncated)
    put("sensors", JSONArray().apply {
        summary.forEach { item ->
            put(JSONObject().apply {
                put("sensor", item.sensor.name)
                put("available", item.available)
                put("event_count", item.eventCount)
                put("first_timestamp_ns", item.firstTimestampNs)
                put("last_timestamp_ns", item.lastTimestampNs)
                put("max_gap_seconds", item.maxGapSeconds)
                put("timestamps_increasing", item.timestampsIncreasing)
                put("counter_monotonic", item.counterMonotonic)
            })
        }
    })
}.toString(2)
