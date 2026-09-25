package dev.ratemock.app.simulation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
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
import dev.ratemock.core.truth.InteractiveSimulation
import dev.ratemock.core.truth.SimulatorGait
import dev.ratemock.core.truth.SimulationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    var startingAt by remember { mutableStateOf(0L) }
    var launchFailed by remember { mutableStateOf(false) }
    var notificationAllowed by remember { mutableStateOf(hasNotificationPermission(context)) }
    LaunchedEffect(context) {
        while (true) {
            snapshot = withContext(Dispatchers.IO) { readSnapshot(context) }
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
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
        Text(stringResource(R.string.sim_title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.sim_model_note), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
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
                    }, enabled = notificationAllowed && validProfile && startingAt == 0L, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (startingAt != 0L) R.string.sim_starting else R.string.sim_start))
                    }
                }
            }
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

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun readSnapshot(context: Context): SimulatorUiSnapshot {
    val preferences = context.getSharedPreferences(SimulatorService.PREFS, Context.MODE_PRIVATE)
    val rawStatus = preferences.getString(SimulatorService.KEY_STATUS, "IDLE") ?: "IDLE"
    val heartbeat = preferences.getLong(SimulatorService.KEY_HEARTBEAT_MS, 0L)
    val status = if (rawStatus == SimulationStatus.RUNNING.name &&
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
    )
}
