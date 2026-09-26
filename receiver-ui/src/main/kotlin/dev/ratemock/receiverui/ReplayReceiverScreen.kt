package dev.ratemock.receiverui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ratemock.receiverui.R
import dev.ratemock.core.replay.LocalReplayEvent
import dev.ratemock.core.replay.LocalReplayValidator
import dev.ratemock.core.replay.ReplayValidation
import dev.ratemock.core.replay.ReplayValidationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

private const val MAX_INPUT_BYTES = 1_048_576L

@Composable
fun ReplayReceiverScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ReceiverResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            result = null
            loading = true
            try {
                result = readReceiverInput(context, uri)
            } finally {
                loading = false
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.receiver_title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.receiver_description))
        Button(onClick = { picker.launch(arrayOf("application/json", "text/*")) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.receiver_choose_file))
        }
        OutlinedButton(onClick = { result = sampleReceiverResult() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.receiver_sample))
        }
        if (loading) Text(stringResource(R.string.receiver_loading), color = MaterialTheme.colorScheme.primary)
        result?.let { receiverResult ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(when {
                        receiverResult.error != null -> R.string.receiver_rejected
                        receiverResult.validation?.status == ReplayValidationStatus.INVALID -> R.string.receiver_invalid
                        receiverResult.validation?.status == ReplayValidationStatus.WARNING -> R.string.receiver_warning
                        else -> R.string.receiver_complete
                    }), style = MaterialTheme.typography.titleMedium)
                    receiverResult.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    receiverResult.validation?.let { validation ->
                        Text(stringResource(R.string.receiver_status, validation.status))
                        Text(stringResource(R.string.receiver_summary, validation.eventCount, validation.durationSeconds))
                        Text(stringResource(R.string.receiver_issues, validation.issues.size))
                        validation.issues.take(8).forEach { issue ->
                            Text(stringResource(R.string.receiver_issue, issue.severity, issue.code, issue.sequence))
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.receiver_limits), style = MaterialTheme.typography.bodySmall)
    }
}

data class ReceiverResult(
    val validation: ReplayValidation? = null,
    val error: String? = null,
)

private suspend fun readReceiverInput(context: Context, uri: Uri): ReceiverResult = withContext(Dispatchers.IO) {
    try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > MAX_INPUT_BYTES) error("文件超过 1 MiB")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取文件")
        val source = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        parseReceiverJson(source)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ReceiverResult(error = error.message ?: "输入格式无效")
    }
}

fun parseReceiverJson(source: String): ReceiverResult = runCatching {
    val root = JSONObject(source)
    root.requireKeys(setOf("schema", "provenance", "events"))
    require(root.optString("schema") == "ratemock.local-replay.v1") { "schema 不匹配" }
    val provenance = root.optString("provenance")
    require(provenance == "simulation" || provenance == "replay") { "provenance 不允许" }
    val array = root.optJSONArray("events") ?: error("缺少 events")
    require(array.length() <= 10_000) { "事件超过 10,000 条" }
    val events = (0 until array.length()).map { index -> parseEvent(array.getJSONObject(index), provenance) }
    ReceiverResult(validation = LocalReplayValidator.validate(events))
}.getOrElse { ReceiverResult(error = it.message ?: "输入格式无效") }

private fun parseEvent(item: JSONObject, provenance: String): LocalReplayEvent {
    val sessionId = item.requireString("session_id")
    val sequence = item.requireLong("sequence")
    val time = item.requireDouble("time_s")
    return when (item.requireString("type")) {
        "position" -> {
            item.requireKeys(setOf("type", "session_id", "sequence", "time_s", "east_m", "north_m"))
            dev.ratemock.core.replay.LocalPositionEvent(
                sessionId, sequence, time, item.requireDouble("east_m"), item.requireDouble("north_m"), provenance.toReplayProvenance(),
            )
        }
        "step_detector" -> {
            item.requireKeys(setOf("type", "session_id", "sequence", "time_s"))
            dev.ratemock.core.replay.LocalStepDetectorEvent(sessionId, sequence, time, provenance.toReplayProvenance())
        }
        "step_counter" -> {
            item.requireKeys(setOf("type", "session_id", "sequence", "time_s", "total_steps"))
            dev.ratemock.core.replay.LocalStepCounterEvent(sessionId, sequence, time, item.requireLong("total_steps"), provenance.toReplayProvenance())
        }
        else -> error("未知事件类型")
    }
}

private fun JSONObject.requireKeys(allowed: Set<String>) {
    require(keys().asSequence().toSet() == allowed) { "字段缺失或包含未识别字段" }
}

private fun String.toReplayProvenance() = if (this == "simulation") dev.ratemock.core.replay.ReplayProvenance.SIMULATION else dev.ratemock.core.replay.ReplayProvenance.REPLAY
private fun JSONObject.requireString(key: String): String = (opt(key) as? String)?.takeIf { it.isNotBlank() } ?: error("缺少 $key")
private fun JSONObject.requireDouble(key: String): Double = ((opt(key) as? Number)?.toDouble() ?: error("缺少 $key"))
    .also { require(it.isFinite()) { "$key 不是有限数" } }
private fun JSONObject.requireLong(key: String): Long = (opt(key) as? Number)?.toString()?.toLongOrNull() ?: error("$key 必须是整数")

private fun sampleReceiverResult(): ReceiverResult = parseReceiverJson("""{"schema":"ratemock.local-replay.v1","provenance":"simulation","events":[{"type":"position","session_id":"sample","sequence":0,"time_s":0,"east_m":0,"north_m":0},{"type":"step_detector","session_id":"sample","sequence":1,"time_s":1},{"type":"step_counter","session_id":"sample","sequence":2,"time_s":2,"total_steps":1}]}""")
