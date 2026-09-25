package dev.ratemock.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.ratemock.core.export.RealRunExport
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ratemock.app.recording.RecorderService
import dev.ratemock.app.simulation.SimulatorScreen
import dev.ratemock.app.simulation.SimulatorService
import dev.ratemock.app.ui.RateMockTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RateMockTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            TabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth()) {
                                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.sim_tab)) })
                                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.record_tab)) })
                            }
                            TextButton(onClick = { openBatterySettings() }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.battery_settings)) }
                        }
                        Text(
                            stringResource(R.string.battery_guidance),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (selectedTab == 0) {
                                SimulatorScreen(
                                    onStart = { speed, duration, cadence, fatigue -> startSimulation(speed, duration, cadence, fatigue) },
                                    onCommand = { action ->
                                        runCatching { startService(Intent(this@MainActivity, SimulatorService::class.java).setAction(action)) }
                                    },
                                    onSetSpeed = { speed ->
                                        runCatching {
                                            startService(Intent(this@MainActivity, SimulatorService::class.java)
                                                .setAction(SimulatorService.ACTION_SET_SPEED)
                                                .putExtra(SimulatorService.EXTRA_SPEED_MPS, speed))
                                        }
                                    },
                                    onRequestNotification = { requestSimulationNotification() },
                                )
                            } else {
                                RecorderScreen(
                                    onRequestPermissions = { requestRecordingPermissions() },
                                    onStartRecording = { cadence, voiceEnabled ->
                                        val permissionsGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                                            android.content.pm.PackageManager.PERMISSION_GRANTED ||
                                            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                                            android.content.pm.PackageManager.PERMISSION_GRANTED
                                        if (!permissionsGranted || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
                                            android.content.pm.PackageManager.PERMISSION_GRANTED)) {
                                            false
                                        } else runCatching {
                                            val intent = Intent(this@MainActivity, RecorderService::class.java)
                                                .setAction(RecorderService.ACTION_START)
                                                .putExtra(RecorderService.EXTRA_TARGET_CADENCE_SPM, cadence)
                                                .putExtra(RecorderService.EXTRA_VOICE_ENABLED, voiceEnabled)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                                        }.isSuccess
                                    },
                                    onStopRecording = {
                                        startService(Intent(this@MainActivity, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        val guidance = getSharedPreferences(BATTERY_PREFS, MODE_PRIVATE)
        if (!guidance.getBoolean(KEY_BATTERY_GUIDANCE_SHOWN, false)) {
            guidance.edit().putBoolean(KEY_BATTERY_GUIDANCE_SHOWN, true).apply()
            openBatterySettings()
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        } catch (_: ActivityNotFoundException) {
            // Some devices do not provide an app-details settings activity.
        } catch (_: SecurityException) {
            // Keep the app usable when a ROM blocks this settings activity.
        }
    }

    private companion object {
        const val BATTERY_PREFS = "battery_guidance"
        const val KEY_BATTERY_GUIDANCE_SHOWN = "shown"
    }

    private fun startSimulation(speed: Double, duration: Double, cadence: Double?, fatigue: Double): Boolean = runCatching {
        val intent = Intent(this, SimulatorService::class.java)
            .setAction(SimulatorService.ACTION_START)
            .putExtra(SimulatorService.EXTRA_SPEED_MPS, speed)
            .putExtra(SimulatorService.EXTRA_DURATION_SECONDS, duration)
            .apply { cadence?.let { putExtra(SimulatorService.EXTRA_MANUAL_CADENCE_SPM, it) } }
            .putExtra(SimulatorService.EXTRA_FATIGUE_REDUCTION, fatigue)
        startForegroundService(intent)
    }.isSuccess

    private fun requestSimulationNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private fun requestRecordingPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        permissionLauncher.launch(permissions)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
}


@Composable
private fun RecorderScreen(
    onRequestPermissions: () -> Unit,
    onStartRecording: (Double, Boolean) -> Boolean,
    onStopRecording: () -> Unit,
) {
    val context = LocalContext.current
    var targetCadence by rememberSaveable { mutableFloatStateOf(170f) }
    var voiceEnabled by rememberSaveable { mutableStateOf(false) }
    var startFailed by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(
                    stringResource(R.string.recorder_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(stringResource(R.string.recorder_description), style = MaterialTheme.typography.bodyMedium)
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.recorder_permissions))
                        }
                        Text(stringResource(R.string.real_target_cadence, targetCadence.roundToInt()))
                        Slider(value = targetCadence, onValueChange = { targetCadence = (it / 5f).roundToInt() * 5f }, valueRange = 90f..210f)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.real_voice_toggle), modifier = Modifier.weight(1f))
                            Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
                        }
                        Button(onClick = { startFailed = !onStartRecording(targetCadence.toDouble(), voiceEnabled) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.recorder_start))
                        }
                        if (startFailed) Text(stringResource(R.string.real_start_error), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onStopRecording, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.recorder_stop))
                        }
                    }
                }
                RecorderPanel(context)
                Text(
                    stringResource(R.string.privacy_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private data class RecorderUiSnapshot(
    val active: Boolean,
    val fileName: String,
    val sampleCount: Int,
    val freshFixes: Int,
    val gpsFresh: Boolean,
    val heartbeatAgeMs: Long?,
    val targetCadenceSpm: Float,
    val actualCadenceSpm: Float?,
    val cadenceSource: String,
    val voiceStatus: String,
    val locationQuality: String,
    val counterQuality: String,
    val summary: String,
    val latest: String,
    val logs: List<String>,
)

@Composable
private fun RecorderPanel(context: Context) {
    var snapshot by remember { mutableStateOf(readRecorderSnapshot(context)) }
    var exportMenu by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exportFile by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(uri: Uri?, format: String) {
        if (uri == null) return
        scope.launch {
            exportError = withContext(Dispatchers.IO) {
                runCatching<String?> {
                    val fileName = exportFile ?: error("No recording selected")
                    val recording = loadStoppedRecording(context, fileName)
                    val contents = when (format) {
                        "csv" -> RealRunExport.csv(recording)
                        "json" -> RealRunExport.json(recording)
                        "gpx" -> RealRunExport.gpx(recording)
                        else -> error("Unsupported format")
                    }
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(contents) }
                        ?: error("Document is not writable")
                    null
                }.getOrElse { context.getString(R.string.real_export_error) }
            }
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { save(it, "csv") }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { save(it, "json") }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { save(it, "gpx") }
    LaunchedEffect(context) {
        while (true) {
            snapshot = withContext(Dispatchers.IO) { readRecorderSnapshot(context) }
            delay(1_000)
        }
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.recorder_status_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (snapshot.active) stringResource(R.string.recorder_status_active)
                else stringResource(R.string.recorder_status_inactive),
                color = if (snapshot.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.recorder_file, snapshot.fileName))
            Text(stringResource(R.string.recorder_samples, snapshot.sampleCount))
            if (snapshot.active) {
                Text(stringResource(R.string.real_target_cadence, snapshot.targetCadenceSpm.roundToInt()))
                Text(snapshot.actualCadenceSpm?.let { stringResource(R.string.real_actual_cadence, it) }
                    ?: stringResource(if (snapshot.cadenceSource == "warming_up") R.string.real_cadence_warming else R.string.real_cadence_unavailable))
                Text(stringResource(R.string.real_location_status, snapshot.locationQuality))
                Text(stringResource(when {
                    snapshot.actualCadenceSpm == null -> R.string.real_voice_waiting
                    !snapshot.gpsFresh -> R.string.real_voice_gps_stale
                    snapshot.actualCadenceSpm < snapshot.targetCadenceSpm - 5 -> R.string.real_voice_increase
                    snapshot.actualCadenceSpm > snapshot.targetCadenceSpm + 5 -> R.string.real_voice_decrease
                    else -> R.string.real_voice_hold
                }, snapshot.targetCadenceSpm.roundToInt()))
                Text(stringResource(R.string.real_counter_status, when (snapshot.counterQuality) {
                    "RESET" -> stringResource(R.string.real_counter_reset)
                    "STALE" -> stringResource(R.string.real_counter_stale)
                    "DIFFERENT" -> stringResource(R.string.real_counter_different)
                    "CONSISTENT" -> stringResource(R.string.real_counter_consistent)
                    "BASELINE" -> stringResource(R.string.real_counter_baseline)
                    else -> stringResource(R.string.real_counter_waiting)
                }))
            }
            Text(
                snapshot.heartbeatAgeMs?.let { age -> stringResource(R.string.recorder_heartbeat, age / 1_000L) }
                    ?: stringResource(R.string.recorder_no_heartbeat),
            )
            Text(snapshot.summary)
            Text(stringResource(R.string.real_provenance, snapshot.freshFixes))
            Text(stringResource(R.string.real_voice_status, when (snapshot.voiceStatus) {
                "ready" -> stringResource(R.string.real_voice_ready)
                "unavailable" -> stringResource(R.string.real_voice_unavailable)
                "initializing" -> stringResource(R.string.real_voice_initializing)
                else -> stringResource(R.string.real_voice_disabled)
            }))
            Box {
                OutlinedButton(
                    enabled = !snapshot.active && snapshot.sampleCount > 0,
                    onClick = { exportMenu = true },
                ) { Text(stringResource(R.string.real_export)) }
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    listOf("csv", "json", "gpx").forEach { format ->
                        DropdownMenuItem(text = { Text(format.uppercase()) }, onClick = {
                            exportMenu = false
                            scope.launch {
                                val fileName = snapshot.fileName
                                exportError = withContext(Dispatchers.IO) {
                                    runCatching { loadStoppedRecording(context, fileName) }
                                        .exceptionOrNull()?.let { context.getString(R.string.real_export_error) }
                                }
                                if (exportError == null) {
                                    exportFile = fileName
                                    val name = fileName.removeSuffix(".csv") + ".$format"
                                    when (format) {
                                        "csv" -> exportCsv.launch(name)
                                        "json" -> exportJson.launch(name)
                                        "gpx" -> exportGpx.launch(name)
                                    }
                                }
                            }
                        })
                    }
                }
            }
            exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.recorder_latest), style = MaterialTheme.typography.labelLarge)
            Text(snapshot.latest, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Text(stringResource(R.string.recorder_log_title), style = MaterialTheme.typography.labelLarge)
            if (snapshot.logs.isEmpty()) {
                Text(stringResource(R.string.recorder_no_logs), style = MaterialTheme.typography.bodySmall)
            } else {
                snapshot.logs.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}

private fun loadStoppedRecording(context: Context, fileName: String): RealRunExport.Recording {
    require(fileName.endsWith(".csv") && fileName.matches(Regex("recording-[0-9]+\\.csv")))
    val preferences = context.getSharedPreferences("recorder_status", Context.MODE_PRIVATE)
    require(!preferences.getBoolean(RecorderService.KEY_ACTIVE, false) ||
        System.currentTimeMillis() - preferences.getLong(RecorderService.KEY_HEARTBEAT_MS, 0L) >= 3_000L) { "Recording is active" }
    val file = File(File(context.filesDir, "recordings"), fileName)
    require(file.isFile && file.parentFile == File(context.filesDir, "recordings")) { "Recording file unavailable" }
    return RealRunExport.parse(file.readText())
}

private fun readRecorderSnapshot(context: Context): RecorderUiSnapshot {
    val directory = File(context.filesDir, "recordings")
    val file = directory.listFiles { candidate -> candidate.extension == "csv" }
        ?.maxByOrNull { it.lastModified() }
    val preferences = context.getSharedPreferences("recorder_status", Context.MODE_PRIVATE)
    val heartbeat = preferences.getLong(RecorderService.KEY_HEARTBEAT_MS, 0L)
    val active = preferences.getBoolean(RecorderService.KEY_ACTIVE, false) &&
        System.currentTimeMillis() - heartbeat in 0L..2_999L
    val lastStepNs = preferences.getLong(RecorderService.KEY_LAST_STEP_NS, 0L)
    val lastFixNs = preferences.getLong(RecorderService.KEY_LOCATION_FIX_NS, 0L)
    val nowNs = SystemClock.elapsedRealtimeNanos()
    var sampleCount = 0
    var freshFixes = 0
    var firstElapsed = 0.0
    var lastElapsed = 0.0
    var firstStep: Double? = null
    var lastStep: Double? = null
    var latest = context.getString(R.string.recorder_no_data)
    val recent = ArrayDeque<String>()
    file?.runCatching {
        bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                sampleCount += 1
                val columns = line.split(',')
                val elapsed = columns.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val age = columns.getOrNull(8)?.toDoubleOrNull()
                val accuracy = columns.getOrNull(5)?.toDoubleOrNull()
                if (age != null && age in 0.0..5.0 && accuracy != null && accuracy in 0.0..25.0) freshFixes++
                val steps = columns.getOrNull(7)?.toDoubleOrNull()
                if (sampleCount == 1) firstElapsed = elapsed
                lastElapsed = elapsed
                if (sampleCount == 1) firstStep = steps
                lastStep = steps
                latest = line
                recent.addLast(line)
                if (recent.size > 20) recent.removeFirst()
            }
        }
    }
    return RecorderUiSnapshot(
        active = active,
        fileName = file?.name ?: context.getString(R.string.recorder_no_file),
        sampleCount = sampleCount,
        freshFixes = freshFixes,
        gpsFresh = lastFixNs > 0L && nowNs - lastFixNs in 0L..5_000_000_000L &&
            preferences.getString(RecorderService.KEY_LOCATION_QUALITY, "waiting") == "fresh",
        heartbeatAgeMs = if (heartbeat == 0L) null else (System.currentTimeMillis() - heartbeat).coerceAtLeast(0L),
        targetCadenceSpm = preferences.getFloat(RecorderService.KEY_TARGET_CADENCE_SPM, 170f),
        actualCadenceSpm = preferences.getFloat(RecorderService.KEY_CADENCE_SPM, 0f)
            .takeIf { active && preferences.getBoolean(RecorderService.KEY_CADENCE_AVAILABLE, false) &&
                lastStepNs > 0L && nowNs - lastStepNs in 0L..3_000_000_000L },
        cadenceSource = preferences.getString(RecorderService.KEY_CADENCE_SOURCE, "unavailable") ?: "unavailable",
        voiceStatus = preferences.getString(RecorderService.KEY_VOICE_STATUS, "disabled") ?: "disabled",
        locationQuality = if (lastFixNs == 0L) "等待 GPS" else if (nowNs - lastFixNs !in 0L..5_000_000_000L) "定位已过期"
            else if (preferences.getString(RecorderService.KEY_LOCATION_QUALITY, "waiting") == "fresh") "定位新鲜" else "精度较低",
        counterQuality = preferences.getString(RecorderService.KEY_COUNTER_QUALITY, "WAITING")?.let { quality ->
            val counterNs = preferences.getLong(RecorderService.KEY_LAST_COUNTER_NS, 0L)
            if (quality != "RESET" && counterNs > 0L && nowNs - counterNs > 5_000_000_000L) "STALE" else quality
        } ?: "WAITING",
        summary = if (sampleCount == 0) {
            context.getString(R.string.recorder_no_summary)
        } else {
            context.getString(
                R.string.recorder_summary,
                sampleCount,
                (lastElapsed - firstElapsed).coerceAtLeast(0.0),
                ((lastStep ?: firstStep ?: 0.0) - (firstStep ?: lastStep ?: 0.0)).coerceAtLeast(0.0),
            )
        },
        latest = latest,
        logs = recent.toList(),
    )
}
