package dev.ratemock.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RateMockTheme {
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.sim_tab)) })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.record_tab)) })
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        if (selectedTab == 0) {
                            SimulatorScreen(
                                onStart = { speed, duration -> startSimulation(speed, duration) },
                                onCommand = { action ->
                                    runCatching { startService(Intent(this@MainActivity, SimulatorService::class.java).setAction(action)) }
                                },
                                onRequestNotification = { requestSimulationNotification() },
                            )
                        } else {
                            RecorderScreen(
                                onRequestPermissions = { requestRecordingPermissions() },
                                onStartRecording = {
                                    val intent = Intent(this@MainActivity, RecorderService::class.java).setAction(RecorderService.ACTION_START)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
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

    private fun startSimulation(speed: Double, duration: Double): Boolean = runCatching {
        val intent = Intent(this, SimulatorService::class.java)
            .setAction(SimulatorService.ACTION_START)
            .putExtra(SimulatorService.EXTRA_SPEED_MPS, speed)
            .putExtra(SimulatorService.EXTRA_DURATION_SECONDS, duration)
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
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val context = LocalContext.current
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
                        Button(onClick = onStartRecording, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.recorder_start))
                        }
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
    val heartbeatAgeMs: Long?,
    val summary: String,
    val latest: String,
    val logs: List<String>,
)

@Composable
private fun RecorderPanel(context: Context) {
    var snapshot by remember { mutableStateOf(readRecorderSnapshot(context)) }
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
            Text(
                snapshot.heartbeatAgeMs?.let { age -> stringResource(R.string.recorder_heartbeat, age / 1_000L) }
                    ?: stringResource(R.string.recorder_no_heartbeat),
            )
            Text(snapshot.summary)
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

private fun readRecorderSnapshot(context: Context): RecorderUiSnapshot {
    val directory = File(context.filesDir, "recordings")
    val file = directory.listFiles { candidate -> candidate.extension == "csv" }
        ?.maxByOrNull { it.lastModified() }
    val preferences = context.getSharedPreferences("recorder_status", Context.MODE_PRIVATE)
    val heartbeat = preferences.getLong(RecorderService.KEY_HEARTBEAT_MS, 0L)
    val active = preferences.getBoolean(RecorderService.KEY_ACTIVE, false) &&
        System.currentTimeMillis() - heartbeat < 3_000L
    var sampleCount = 0
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
        heartbeatAgeMs = if (heartbeat == 0L) null else (System.currentTimeMillis() - heartbeat).coerceAtLeast(0L),
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
