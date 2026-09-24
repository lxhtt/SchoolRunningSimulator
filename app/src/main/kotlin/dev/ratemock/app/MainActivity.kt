
private data class RecorderUiSnapshot(
    val active: Boolean,
    val fileName: String,
    val sampleCount: Int,
    val latest: String,
    val logs: List<String>,
)

@Composable
private fun RecorderPanel(context: Context) {
    var snapshot by remember { mutableStateOf(readRecorderSnapshot(context)) }
    LaunchedEffect(context) {
        while (true) {
            snapshot = readRecorderSnapshot(context)
            delay(1_000)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.recorder_status_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (snapshot.active) stringResource(R.string.recorder_status_active)
                else stringResource(R.string.recorder_status_inactive),
                color = if (snapshot.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.recorder_file, snapshot.fileName))
            Text(stringResource(R.string.recorder_samples, snapshot.sampleCount))
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
    val lines = file?.runCatching { readLines() }?.getOrDefault(emptyList()).orEmpty()
    val preferences = context.getSharedPreferences("recorder_status", Context.MODE_PRIVATE)
    val heartbeat = preferences.getLong(RecorderService.KEY_HEARTBEAT_MS, 0L)
    val active = preferences.getBoolean(RecorderService.KEY_ACTIVE, false) &&
        System.currentTimeMillis() - heartbeat < 3_000L
    val data = lines.drop(1)
    return RecorderUiSnapshot(
        active = active,
        fileName = file?.name ?: context.getString(R.string.recorder_no_file),
        sampleCount = data.size,
        latest = data.lastOrNull() ?: context.getString(R.string.recorder_no_data),
        logs = data.takeLast(20),
    )
}
