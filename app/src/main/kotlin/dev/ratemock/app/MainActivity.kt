package dev.ratemock.app

import android.Manifest
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ratemock.app.recording.RecorderService
import dev.ratemock.app.ui.RateMockTheme
import dev.ratemock.core.GaitKinematics

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RateMockTheme {
                BuildVerificationScreen(
                    onRequestPermissions = { requestRecordingPermissions() },
                    onStartRecording = {
                        val intent = Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                    },
                    onStopRecording = {
                        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                    },
                )
            }
        }
    }

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
private fun BuildVerificationScreen(
    onRequestPermissions: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    // Explicit sample inputs: not a measurement or a recommended running cadence.
    val speed = GaitKinematics.speedMetersPerSecond(180.0, 0.9)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
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
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.build_stage),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.screen_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(stringResource(R.string.screen_description), style = MaterialTheme.typography.bodyLarge)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.sample_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.sample_inputs), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.speed_result, speed),
                            style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace),
                        )
                        Text(stringResource(R.string.speed_label), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.speed_formula), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(R.string.next_phase), style = MaterialTheme.typography.bodyLarge)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.recorder_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.recorder_description), style = MaterialTheme.typography.bodyMedium)
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
                Text(
                    stringResource(R.string.privacy_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
