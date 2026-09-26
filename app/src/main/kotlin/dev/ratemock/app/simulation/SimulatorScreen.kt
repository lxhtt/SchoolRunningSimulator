package dev.ratemock.app.simulation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ratemock.app.R
import dev.ratemock.core.export.CsvExporter
import dev.ratemock.core.export.ExportBundle
import dev.ratemock.core.export.GpsObservationBuilder
import dev.ratemock.core.export.GpxExporter
import dev.ratemock.core.export.JsonExporter
import dev.ratemock.core.route.GeoPoint
import dev.ratemock.core.route.Route
import dev.ratemock.core.truth.TruthSample
import dev.ratemock.core.truth.RunState
import dev.ratemock.core.truth.InteractiveSimulation
import dev.ratemock.core.truth.SimulatorGait
import dev.ratemock.core.truth.SimulationStatus
import dev.ratemock.core.truth.SimulationPreflight
import dev.ratemock.core.truth.SimulationPreflightInput
import dev.ratemock.core.truth.PreflightSeverity
import dev.ratemock.core.truth.WaveformReview
import dev.ratemock.core.truth.WaveformReviewStatus
import dev.ratemock.core.truth.WaveformSample
import dev.ratemock.core.replay.LocalPositionEvent
import dev.ratemock.core.replay.LocalReplayValidator
import dev.ratemock.core.replay.ReplayIssueSeverity
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

private data class HistoryPoint(
    val elapsedSeconds: Float,
    val distanceM: Float,
    val speedMps: Float,
    val cadenceSpm: Float,
    val eastM: Float,
    val northM: Float,
)

private data class ReplayDiagnostic(
    val status: String,
    val events: Int,
    val durationSeconds: Float,
    val warnings: Int,
)

private data class SimulatorUiSnapshot(
    val status: String = "IDLE",
    val mode: String = "",
    val targetSpeedMps: Float = 2.7f,
    val durationSeconds: Float = 600f,
    val elapsedSeconds: Float = 0f,
    val distanceMeters: Float = 0f,
    val speedMps: Float = 0f,
    val cadenceSpm: Float = 0f,
    val manualCadenceSpm: Float = 0f,
    val fatigueReduction: Float = 0f,
    val error: String = "",
    val steps: Long = 0L,
    val historyFile: String = "",
)

@Composable
fun SimulatorScreen(
    onStart: (Double, Double, Double?, Double) -> Boolean,
    onCommand: (String) -> Unit,
    onSetSpeed: (Double) -> Unit,
    onRequestNotification: () -> Unit,
) {
    val context = LocalContext.current
    var targetSpeed by rememberSaveable { mutableFloatStateOf(2.7f) }
    var durationMinutes by rememberSaveable { mutableFloatStateOf(10f) }
    var manualMode by rememberSaveable { mutableStateOf(false) }
    var manualCadence by rememberSaveable { mutableFloatStateOf(160f) }
    var fatigueReduction by rememberSaveable { mutableFloatStateOf(0f) }
    var lastObservedTarget by remember { mutableFloatStateOf(Float.NaN) }
    var snapshot by remember { mutableStateOf(readSnapshot(context)) }
    var history by remember { mutableStateOf(emptyList<HistoryPoint>()) }
    val replayDiagnostic = remember(history) { diagnoseHistory(history) }
    val waveformReview = remember(history) {
        WaveformReview.review(history.map { WaveformSample(it.elapsedSeconds.toDouble(), it.speedMps.toDouble(), it.cadenceSpm.toDouble()) })
    }
    val exportScope = rememberCoroutineScope()
    var exportMenu by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    var exportSucceeded by remember { mutableStateOf(false) }
    var exportWriting by remember { mutableStateOf(false) }
    var exportSnapshot by remember { mutableStateOf<SimulatorUiSnapshot?>(null) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val selected = exportSnapshot
        if (uri != null && selected != null) exportScope.launch {
            exportWriting = true
            exportFailed = false
            exportSucceeded = false
            exportSucceeded = exportHistory(context, uri, selected, "csv")
            exportFailed = !exportSucceeded
            exportWriting = false
        }
        exportSnapshot = null
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val selected = exportSnapshot
        if (uri != null && selected != null) exportScope.launch {
            exportWriting = true
            exportFailed = false
            exportSucceeded = false
            exportSucceeded = exportHistory(context, uri, selected, "json")
            exportFailed = !exportSucceeded
            exportWriting = false
        }
        exportSnapshot = null
    }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val selected = exportSnapshot
        if (uri != null && selected != null) exportScope.launch {
            exportWriting = true
            exportFailed = false
            exportSucceeded = false
            exportSucceeded = exportHistory(context, uri, selected, "gpx")
            exportFailed = !exportSucceeded
            exportWriting = false
        }
        exportSnapshot = null
    }
    val exportEvents = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val selected = exportSnapshot
        if (uri != null && selected != null) exportScope.launch {
            exportWriting = true
            exportFailed = false
            exportSucceeded = false
            exportSucceeded = exportHistory(context, uri, selected, "events")
            exportFailed = !exportSucceeded
            exportWriting = false
        }
        exportSnapshot = null
    }
    var startingAt by remember { mutableStateOf(0L) }
    var launchFailed by remember { mutableStateOf(false) }
    var notificationAllowed by remember { mutableStateOf(hasNotificationPermission(context)) }
    LaunchedEffect(context) {
        while (true) {
            snapshot = withContext(Dispatchers.IO) { readSnapshot(context) }
            history = withContext(Dispatchers.IO) { readSimulationHistory(context, snapshot.historyFile) }
            notificationAllowed = hasNotificationPermission(context)
            if (snapshot.status == SimulationStatus.RUNNING.name || snapshot.status == SimulationStatus.PAUSED.name) {
                if (snapshot.targetSpeedMps != lastObservedTarget) {
                    targetSpeed = snapshot.targetSpeedMps
                    lastObservedTarget = snapshot.targetSpeedMps
                }
                durationMinutes = snapshot.durationSeconds / 60f
                if (snapshot.manualCadenceSpm > 0f) {
                    manualMode = true
                    manualCadence = snapshot.manualCadenceSpm
                }
                fatigueReduction = snapshot.fatigueReduction
            }
            if (startingAt != 0L && (snapshot.status == SimulationStatus.RUNNING.name ||
                snapshot.status == SimulationStatus.PAUSED.name ||
                System.currentTimeMillis() - startingAt > 5_000L)) startingAt = 0L
            delay(1_000)
        }
    }
    val active = snapshot.status == SimulationStatus.RUNNING.name || snapshot.status == SimulationStatus.PAUSED.name
    val mode = if (active || snapshot.status == SimulationStatus.STOPPED.name ||
        snapshot.status == SimulationStatus.COMPLETED.name || snapshot.status == SimulatorService.STATUS_INTERRUPTED) {
        snapshot.mode
    } else if (targetSpeed < InteractiveSimulation.MODE_BOUNDARY_MPS) "WALKING" else "RUNNING"
    val modeLabel = if (mode == "WALKING") R.string.sim_mode_walking else R.string.sim_mode_running
    val cadenceLabel = if (if (active || snapshot.status != "IDLE") snapshot.manualCadenceSpm > 0f else manualMode) {
        R.string.sim_mode_manual
    } else R.string.sim_mode_auto
    val validProfile = runCatching {
        SimulatorGait.validateProfile(targetSpeed.toDouble(), if (active) snapshot.manualCadenceSpm.takeIf { it > 0f }?.toDouble()
            else if (manualMode) manualCadence.toDouble() else null, fatigueReduction.toDouble())
    }.isSuccess
    val statusLabel = when (snapshot.status) {
        SimulationStatus.RUNNING.name -> R.string.sim_status_running
        SimulationStatus.PAUSED.name -> R.string.sim_status_paused
        SimulationStatus.STOPPED.name -> R.string.sim_status_stopped
        SimulationStatus.COMPLETED.name -> R.string.sim_status_completed
        SimulatorService.STATUS_INTERRUPTED -> R.string.sim_status_interrupted
        SimulatorService.STATUS_ERROR -> R.string.sim_status_error
        else -> R.string.sim_status_idle
    }
    val preflightHistoryExists = remember(snapshot.historyFile) { hasSimulationHistoryFile(context, snapshot.historyFile) }
    val preflightHistoryReadable = remember(snapshot.historyFile, history) {
        !preflightHistoryExists || history.isNotEmpty()
    }
    val preflight = remember(notificationAllowed, validProfile, preflightHistoryExists, preflightHistoryReadable) {
        SimulationPreflight.check(
            SimulationPreflightInput(
                notificationAllowed = notificationAllowed,
                simulationDirectoryWritable = simulationDirectoryWritable(context),
                historyReadable = preflightHistoryReadable,
                historyExists = preflightHistoryExists,
                validProfile = validProfile,
            ),
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
        Text(stringResource(R.string.sim_title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.sim_model_note), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.sim_presets_title), style = MaterialTheme.typography.titleSmall)
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = {
                targetSpeed = 1.4f
                durationMinutes = 10f
                manualMode = false
                fatigueReduction = 0f
            }, modifier = Modifier.fillMaxWidth(), enabled = !active) {
                Text(stringResource(R.string.sim_preset_walk))
            }
            OutlinedButton(onClick = {
                targetSpeed = 2.7f
                durationMinutes = 10f
                manualMode = false
                fatigueReduction = 0f
            }, modifier = Modifier.fillMaxWidth(), enabled = !active) {
                Text(stringResource(R.string.sim_preset_steady))
            }
            OutlinedButton(onClick = {
                targetSpeed = 3.5f
                durationMinutes = 20f
                manualMode = false
                fatigueReduction = 0f
            }, modifier = Modifier.fillMaxWidth(), enabled = !active) {
                Text(stringResource(R.string.sim_preset_tempo))
            }
        }
        PreflightCard(preflight)
        if (snapshot.status != "IDLE") {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(statusLabel), color = if (snapshot.status == SimulationStatus.RUNNING.name)
                        MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium)
                    Text("${stringResource(modeLabel)} · ${stringResource(cadenceLabel)}", style = MaterialTheme.typography.labelLarge)
                    if (active) {
                        Text(stringResource(R.string.sim_speed_value, snapshot.targetSpeedMps),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.sim_distance_value, snapshot.distanceMeters),
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace))
                    Text(stringResource(R.string.sim_distance), style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { (snapshot.elapsedSeconds / snapshot.durationSeconds).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val elapsed = snapshot.elapsedSeconds.toInt().coerceAtLeast(0)
                    val total = snapshot.durationSeconds.toInt().coerceAtLeast(0)
                    Text(stringResource(R.string.sim_time_value, elapsed / 60, elapsed % 60, total / 60, total % 60),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric(stringResource(R.string.sim_speed), stringResource(R.string.sim_current_speed_value, snapshot.speedMps), Modifier.weight(1f))
                        Metric(stringResource(R.string.sim_cadence), stringResource(R.string.sim_cadence_value, snapshot.cadenceSpm), Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.sim_steps_value, snapshot.steps), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (history.isNotEmpty()) {
                SimulationHistory(history)
                WaveformReviewCard(waveformReview)
                ReplayDiagnosticCard(replayDiagnostic)
                if (!active) {
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { exportMenu = true }, enabled = !exportWriting) { Text(stringResource(R.string.sim_export)) }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            listOf("csv", "json", "gpx", "events").forEach { format ->
                                DropdownMenuItem(text = { Text(if (format == "events") stringResource(R.string.sim_export_events) else format.uppercase()) }, onClick = {
                                    exportMenu = false
                                    exportSnapshot = snapshot
                                    exportFailed = false
                                    exportSucceeded = false
                                    val fileName = snapshot.historyFile.removeSuffix(".csv") + if (format == "events") ".events.json" else ".$format"
                                    when (format) {
                                        "csv" -> exportCsv.launch(fileName)
                                        "json" -> exportJson.launch(fileName)
                                        "gpx" -> exportGpx.launch(fileName)
                                        else -> exportEvents.launch(fileName)
                                    }
                                })
                            }
                        }
                    }
                }
                if (exportWriting) Text(stringResource(R.string.sim_export_writing), color = MaterialTheme.colorScheme.primary)
                if (exportSucceeded) Text(stringResource(R.string.sim_export_success), color = MaterialTheme.colorScheme.primary)
                if (exportFailed) Text(stringResource(R.string.sim_export_error), color = MaterialTheme.colorScheme.error)
            }
        }
        if (active) {
            Text(stringResource(R.string.sim_live_speed), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.sim_speed_value, targetSpeed), style = MaterialTheme.typography.titleLarge)
            Slider(value = targetSpeed, onValueChange = { targetSpeed = (it * 10).roundToInt() / 10f },
                valueRange = InteractiveSimulation.MIN_SPEED_MPS.toFloat()..InteractiveSimulation.MAX_SPEED_MPS.toFloat(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ))
            OutlinedButton(onClick = { onSetSpeed(targetSpeed.toDouble()) }, enabled = validProfile, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sim_apply_speed))
            }
            if (!validProfile) Text(stringResource(R.string.sim_infeasible_note), color = MaterialTheme.colorScheme.error)
        }
        if (!active) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.sim_speed_target), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.sim_speed_value, targetSpeed),
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace))
                Text(stringResource(if (manualMode) R.string.sim_mode_manual else R.string.sim_mode_auto),
                    color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                if (manualMode) {
                    Text(stringResource(R.string.sim_cadence_value, manualCadence), style = MaterialTheme.typography.bodyMedium)
                }
                Slider(value = targetSpeed, onValueChange = { targetSpeed = (it * 10).roundToInt() / 10f },
                    valueRange = InteractiveSimulation.MIN_SPEED_MPS.toFloat()..InteractiveSimulation.MAX_SPEED_MPS.toFloat(),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { manualMode = false }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.sim_mode_auto)) }
                    OutlinedButton(onClick = { manualMode = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.sim_mode_manual)) }
                }
                if (manualMode) {
                    Text(stringResource(R.string.sim_manual_cadence), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.sim_cadence_value, manualCadence), style = MaterialTheme.typography.titleLarge)
                    Slider(value = manualCadence, onValueChange = { manualCadence = (it / 5f).roundToInt() * 5f },
                        valueRange = SimulatorGait.MIN_CADENCE_SPM.toFloat()..SimulatorGait.MAX_CADENCE_SPM.toFloat(),
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.tertiary,
                            activeTrackColor = MaterialTheme.colorScheme.tertiary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        ))
                    Text(stringResource(R.string.sim_step_length_value, targetSpeed * 60f / manualCadence), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!validProfile) Text(stringResource(R.string.sim_invalid_profile_note), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.sim_fatigue_reduction), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.sim_percent_value, (fatigueReduction * 100).roundToInt()), style = MaterialTheme.typography.titleLarge)
                Slider(value = fatigueReduction, onValueChange = { fatigueReduction = (it * 20).roundToInt() / 20f },
                    valueRange = 0f..SimulatorGait.MAX_FATIGUE_REDUCTION.toFloat(),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.secondary,
                        activeTrackColor = MaterialTheme.colorScheme.secondary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ))
                Text(stringResource(R.string.sim_duration), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.sim_minutes_value, durationMinutes.roundToInt()), style = MaterialTheme.typography.titleLarge)
                Slider(value = durationMinutes, onValueChange = { durationMinutes = it.roundToInt().toFloat() },
                    valueRange = 1f..30f)
            }
        }
        if (!notificationAllowed) {
            Text(stringResource(R.string.sim_permission_needed), color = MaterialTheme.colorScheme.error)
            Button(onClick = onRequestNotification, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sim_permission))
            }
        }
        if (snapshot.error == SimulatorService.ERROR_INFEASIBLE) {
            Text(stringResource(R.string.sim_infeasible_note), color = MaterialTheme.colorScheme.error)
        }
        if (snapshot.status == SimulatorService.STATUS_INTERRUPTED) {
            Text(stringResource(R.string.sim_interrupted_note), color = MaterialTheme.colorScheme.error)
        }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (active) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            onCommand(if (snapshot.status == SimulationStatus.PAUSED.name) SimulatorService.ACTION_RESUME else SimulatorService.ACTION_PAUSE)
                        }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(if (snapshot.status == SimulationStatus.PAUSED.name) R.string.sim_resume else R.string.sim_pause))
                        }
                        OutlinedButton(onClick = { onCommand(SimulatorService.ACTION_STOP) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.sim_stop))
                        }
                    }
                } else {
                    Button(onClick = {
                        launchFailed = !onStart(targetSpeed.toDouble(), durationMinutes.roundToInt() * 60.0,
                            if (manualMode) manualCadence.toDouble() else null, fatigueReduction.toDouble())
                        if (!launchFailed) startingAt = System.currentTimeMillis()
                    }, enabled = notificationAllowed && validProfile && !preflight.blocked && startingAt == 0L, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (startingAt != 0L) R.string.sim_starting else R.string.sim_start))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreflightCard(result: dev.ratemock.core.truth.SimulationPreflightResult) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.sim_preflight_title), style = MaterialTheme.typography.titleMedium)
            result.checks.forEach { check ->
                val label = when (check.key) {
                    "notifications" -> R.string.sim_preflight_notifications
                    "simulation_directory" -> R.string.sim_preflight_directory
                    "history" -> R.string.sim_preflight_history
                    "parameters" -> R.string.sim_preflight_parameters
                    else -> R.string.sim_preflight_injection
                }
                val status = when (check.severity) {
                    PreflightSeverity.PASS -> R.string.sim_preflight_pass
                    PreflightSeverity.WARNING -> R.string.sim_preflight_warning
                    PreflightSeverity.BLOCKED -> R.string.sim_preflight_blocked
                }
                Text(stringResource(label, stringResource(status)), style = MaterialTheme.typography.bodyMedium,
                    color = when (check.severity) {
                        PreflightSeverity.PASS -> MaterialTheme.colorScheme.onSurface
                        PreflightSeverity.WARNING, PreflightSeverity.BLOCKED -> MaterialTheme.colorScheme.error
                    })
            }
            Text(stringResource(R.string.sim_preflight_note), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SimulationHistory(points: List<HistoryPoint>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.sim_history_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sim_history_provenance), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.sim_speed_curve), style = MaterialTheme.typography.labelLarge)
            HistoryCurve(points.map { it.speedMps }, MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.sim_cadence_curve), style = MaterialTheme.typography.labelLarge)
            HistoryCurve(points.map { it.cadenceSpm }, MaterialTheme.colorScheme.tertiary)
            Text(stringResource(R.string.sim_route_synthetic), style = MaterialTheme.typography.labelLarge)
            HistoryRoute(points)
        }
    }
}

@Composable
private fun HistoryCurve(values: List<Float>, color: androidx.compose.ui.graphics.Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(84.dp)) {
        if (values.size < 2) return@Canvas
        val min = values.minOrNull() ?: return@Canvas
        val max = values.maxOrNull() ?: return@Canvas
        val span = (max - min).coerceAtLeast(0.001f)
        val step = size.width / (values.lastIndex.coerceAtLeast(1))
        values.zipWithNext().forEachIndexed { index, pair ->
            drawLine(color, androidx.compose.ui.geometry.Offset(index * step, size.height - (pair.first - min) / span * size.height),
                androidx.compose.ui.geometry.Offset((index + 1) * step, size.height - (pair.second - min) / span * size.height), strokeWidth = 3f)
        }
    }
}

@Composable
private fun HistoryRoute(points: List<HistoryPoint>) {
    val color = MaterialTheme.colorScheme.secondary
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        if (points.size < 2) return@Canvas
        val minEast = points.minOf { it.eastM }
        val maxEast = points.maxOf { it.eastM }
        val minNorth = points.minOf { it.northM }
        val maxNorth = points.maxOf { it.northM }
        val eastSpan = (maxEast - minEast).coerceAtLeast(0.001f)
        val northSpan = (maxNorth - minNorth).coerceAtLeast(0.001f)
        val scale = min(size.width / eastSpan, size.height / northSpan) * 0.86f
        val offsetX = (size.width - eastSpan * scale) / 2f
        val offsetY = (size.height - northSpan * scale) / 2f
        points.zipWithNext().forEach { (a, b) ->
            drawLine(color,
                androidx.compose.ui.geometry.Offset(offsetX + (a.eastM - minEast) * scale, size.height - offsetY - (a.northM - minNorth) * scale),
                androidx.compose.ui.geometry.Offset(offsetX + (b.eastM - minEast) * scale, size.height - offsetY - (b.northM - minNorth) * scale), strokeWidth = 3f)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
    }
}

private suspend fun exportHistory(context: Context, uri: Uri, snapshot: SimulatorUiSnapshot, format: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val history = readSimulationHistory(context, snapshot.historyFile)
            require(history.isNotEmpty())
            val samples = history.map { point ->
                val speed = point.speedMps.toDouble()
                val cadence = point.cadenceSpm.toDouble()
                TruthSample(point.elapsedSeconds.toDouble(), point.distanceM.toDouble(), point.eastM.toDouble(),
                    point.northM.toDouble(), speed, cadence, if (cadence > 0.0) speed * 60.0 / cadence else 0.0, RunState.RUNNING)
            }
            val syntheticRoute = Route(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.1)))
            val gps = GpsObservationBuilder(syntheticRoute).build(samples)
            val bundle = ExportBundle(samples, emptyList(), gps)
            val data = when (format) {
                "events" -> localReplayJson(history)
                "csv" -> "# provenance=simulation;calibration=uncalibrated\n" + CsvExporter.truth(samples)
                "json" -> JsonExporter.summary(bundle, snapshot.steps, snapshot.elapsedSeconds.toDouble(), snapshot.distanceMeters.toDouble())
                "gpx" -> {
                    val epochMs = snapshot.historyFile.removePrefix("simulation-").removeSuffix(".csv").toLong()
                    GpxExporter.track(gps, Instant.ofEpochMilli(epochMs))
                }
                else -> error("Unsupported export format")
            }
            if (format == "events") require(data.toByteArray(Charsets.UTF_8).size <= 1_048_576) {
                "Event input exceeds receiver limit"
            }
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(data) }
                ?: error("Unable to open selected document")
        }.isSuccess
    }

private fun localReplayJson(history: List<HistoryPoint>): String {
    require(history.isNotEmpty() && history.size <= 10_000)
    val events = history.mapIndexed { index, point ->
        require(point.elapsedSeconds.isFinite() && point.eastM.isFinite() && point.northM.isFinite())
        JSONObject().put("type", "position").put("session_id", "simulation-history")
            .put("sequence", index).put("time_s", point.elapsedSeconds.toDouble())
            .put("east_m", point.eastM.toDouble()).put("north_m", point.northM.toDouble())
    }
    return JSONObject().put("schema", "ratemock.local-replay.v1")
        .put("provenance", "simulation").put("events", JSONArray(events)).toString()
}

@Composable
private fun WaveformReviewCard(result: dev.ratemock.core.truth.WaveformReviewResult) {
    val status = when (result.status) {
        WaveformReviewStatus.EMPTY -> R.string.sim_waveform_empty
        WaveformReviewStatus.INSUFFICIENT -> R.string.sim_waveform_insufficient
        WaveformReviewStatus.READY -> R.string.sim_waveform_ready
        WaveformReviewStatus.INVALID -> R.string.sim_waveform_invalid
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.sim_waveform_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.sim_waveform_status, stringResource(status)))
            if (result.status == WaveformReviewStatus.READY) {
                Text(stringResource(R.string.sim_waveform_summary, result.sampleCount, result.durationSeconds))
                Text(stringResource(R.string.sim_waveform_speed_range, result.minSpeedMps, result.maxSpeedMps))
                Text(stringResource(R.string.sim_waveform_cadence_range, result.minCadenceSpm, result.maxCadenceSpm))
                Text(stringResource(R.string.sim_waveform_step_change, result.maxSpeedStepMps, result.maxCadenceStepSpm))
            }
            Text(stringResource(R.string.sim_waveform_note), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReplayDiagnosticCard(diagnostic: ReplayDiagnostic) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.sim_replay_diagnostic_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.sim_replay_diagnostic_status, diagnostic.status))
            Text(stringResource(R.string.sim_replay_diagnostic_summary, diagnostic.events, diagnostic.durationSeconds, diagnostic.warnings))
            Text(stringResource(R.string.sim_replay_diagnostic_note), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun diagnoseHistory(history: List<HistoryPoint>): ReplayDiagnostic {
    val events = history.mapIndexed { index, point ->
        LocalPositionEvent("simulation-history", index.toLong(), point.elapsedSeconds.toDouble(), point.eastM.toDouble(), point.northM.toDouble())
    }
    val validation = LocalReplayValidator.validate(events)
    return ReplayDiagnostic(validation.status.name, validation.eventCount, validation.durationSeconds.toFloat(),
        validation.issues.count { it.severity == ReplayIssueSeverity.WARNING })
}

private fun hasSimulationHistoryFile(context: Context, fileName: String): Boolean =
    fileName.matches(Regex("simulation-[0-9]+\\.csv")) && File(context.filesDir, "simulations/$fileName").isFile

private fun simulationDirectoryWritable(context: Context): Boolean {
    val directory = File(context.filesDir, "simulations")
    return (directory.isDirectory || directory.mkdirs()) && directory.canWrite()
}

private fun readSimulationHistory(context: Context, fileName: String): List<HistoryPoint> {
    if (!fileName.matches(Regex("simulation-[0-9]+\\.csv"))) return emptyList()
    val file = File(context.filesDir, "simulations/$fileName")
    if (!file.isFile) return emptyList()
    return runCatching {
        file.useLines { lines ->
            lines.drop(2).mapNotNull { line ->
                val c = line.split(',')
                if (c.size < 6) null else HistoryPoint(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat(), c[4].toFloat(), c[5].toFloat())
            }.toList()
        }
    }.getOrDefault(emptyList())
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun readSnapshot(context: Context): SimulatorUiSnapshot {
    val preferences = context.getSharedPreferences(SimulatorService.PREFS, Context.MODE_PRIVATE)
    val rawStatus = preferences.getString(SimulatorService.KEY_STATUS, "IDLE") ?: "IDLE"
    val heartbeat = preferences.getLong(SimulatorService.KEY_HEARTBEAT_MS, 0L)
    val status = if (rawStatus in setOf(SimulationStatus.RUNNING.name, SimulationStatus.PAUSED.name) &&
        (heartbeat == 0L || System.currentTimeMillis() - heartbeat > 5_000L)) {
        SimulatorService.STATUS_INTERRUPTED
    } else rawStatus
    return SimulatorUiSnapshot(
        status = status,
        mode = preferences.getString(SimulatorService.KEY_MODE, "") ?: "",
        targetSpeedMps = preferences.getFloat(SimulatorService.KEY_TARGET_MPS, 2.7f),
        durationSeconds = preferences.getFloat(SimulatorService.KEY_DURATION_SECONDS, 600f),
        elapsedSeconds = preferences.getFloat(SimulatorService.KEY_ELAPSED_SECONDS, 0f),
        distanceMeters = preferences.getFloat(SimulatorService.KEY_DISTANCE_METERS, 0f),
        speedMps = preferences.getFloat(SimulatorService.KEY_SPEED_MPS, 0f),
        cadenceSpm = preferences.getFloat(SimulatorService.KEY_CADENCE_SPM, 0f),
        manualCadenceSpm = preferences.getFloat(SimulatorService.KEY_MANUAL_CADENCE_SPM, 0f),
        fatigueReduction = preferences.getFloat(SimulatorService.KEY_FATIGUE_REDUCTION, 0f),
        error = preferences.getString(SimulatorService.KEY_ERROR, "") ?: "",
        steps = preferences.getLong(SimulatorService.KEY_STEPS, 0L),
        historyFile = preferences.getString(SimulatorService.KEY_HISTORY_FILE, "") ?: "",
    )
}
