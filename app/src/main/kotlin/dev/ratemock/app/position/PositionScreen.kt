package dev.ratemock.app.position

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import dev.ratemock.app.position.MockLocationService
import dev.ratemock.app.simulation.SimulatorService
import dev.ratemock.core.position.PositionPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun PositionScreen(
    onRequestPermissions: () -> Unit,
    onOpenMockSettings: () -> Unit,
    onServiceAction: (String, PositionPoint?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { PositionHistoryStore(context) }
    var latitudeText by rememberSaveable { mutableStateOf("39.9042") }
    var longitudeText by rememberSaveable { mutableStateOf("116.4074") }
    var altitudeText by rememberSaveable { mutableStateOf("0") }
    var point by remember { mutableStateOf<PositionPoint?>(PositionPoint(39.9042, 116.4074)) }
    var route by remember { mutableStateOf(listOf<PositionPoint>()) }
    var name by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(store.entries()) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<SearchResult>()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var serviceStatus by remember { mutableStateOf("IDLE") }
    var serviceError by remember { mutableStateOf<String?>(null) }
    var moveMeters by rememberSaveable { mutableFloatStateOf(10f) }
    var simulationStatus by remember { mutableStateOf("IDLE") }
    var simulationHistoryClosed by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        val prefs = context.getSharedPreferences(MockLocationService.PREFS, 0)
        while (true) {
            val simulationPrefs = context.getSharedPreferences(SimulatorService.PREFS, 0)
            simulationStatus = simulationPrefs.getString(SimulatorService.KEY_STATUS, "IDLE") ?: "IDLE"
            simulationHistoryClosed = simulationPrefs.getBoolean(SimulatorService.KEY_HISTORY_CLOSED, false)
            val stored = prefs.getString(MockLocationService.KEY_STATUS, "IDLE") ?: "IDLE"
            val heartbeatAge = System.currentTimeMillis() - prefs.getLong(MockLocationService.KEY_HEARTBEAT, 0L)
            if (stored in setOf("RUNNING", "PAUSED", "LOADING") && heartbeatAge > 5_000L) {
                serviceStatus = "INTERRUPTED"
                serviceError = "定位服务已中断，请重新启动"
            } else {
                serviceStatus = stored
                serviceError = prefs.getString(MockLocationService.KEY_ERROR, null)
            }
            kotlinx.coroutines.delay(1_000)
        }
    }

    val editedPoint = runCatching {
        PositionPoint(parseCoordinate(latitudeText), parseCoordinate(longitudeText), parseCoordinate(altitudeText))
    }.getOrNull()

    fun applyPoint(candidate: PositionPoint) {
        point = candidate
        latitudeText = candidate.latitude.toString()
        longitudeText = candidate.longitude.toString()
        altitudeText = candidate.altitude.toString()
        route = (route + candidate).takeLast(200)
        if (serviceStatus == "RUNNING") onServiceAction(MockLocationService.ACTION_UPDATE, candidate)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("位置工作台", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                Text("地图、历史和模拟定位服务独立于真实记录与模拟跑台。", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Text("地图路线仅显示本次选点和摇杆轨迹；回放进度显示在服务状态中。", style = MaterialTheme.typography.bodySmall)
                CoordinateMap(point, route, onTap = { lat, lon ->
                    runCatching { applyPoint(PositionPoint(lat, lon, point?.altitude ?: 0.0)) }
                })
            }
            item {
                Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("当前点", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            NumberField("纬度", latitudeText, { latitudeText = it }, Modifier.weight(1f))
                            NumberField("经度", longitudeText, { longitudeText = it }, Modifier.weight(1f))
                        }
                        NumberField("海拔（米）", altitudeText, { altitudeText = it }, Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Button(enabled = editedPoint != null, onClick = {
                                editedPoint?.let { applyPoint(it) }
                            }) { Text("应用坐标") }
                            OutlinedButton(onClick = onRequestPermissions) { Text("定位权限") }
                            OutlinedButton(onClick = onOpenMockSettings) { Text("模拟应用设置") }
                        }
                        if (editedPoint == null) Text("坐标格式或范围无效", color = MaterialTheme.colorScheme.error)
                        Text("模拟路线：${if (simulationHistoryClosed && simulationStatus in setOf("STOPPED", "COMPLETED")) "可回放" else "请先完成并停止模拟"}", style = MaterialTheme.typography.bodySmall)
                        Button(
                            enabled = point != null && simulationHistoryClosed && simulationStatus in setOf("STOPPED", "COMPLETED") && serviceStatus !in setOf("RUNNING", "PAUSED", "LOADING"),
                            onClick = { onServiceAction(MockLocationService.ACTION_ROUTE_START, point) },
                        ) { Text("回放最近模拟路线") }
                        Text("状态：${serviceStatus}${serviceError?.let { " · $it" } ?: ""}", color = if (serviceStatus == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Button(enabled = point != null && editedPoint == point && serviceStatus !in setOf("RUNNING", "PAUSED", "LOADING"), onClick = { onServiceAction(MockLocationService.ACTION_START, point) }) { Text("启动模拟定位") }
                            OutlinedButton(enabled = serviceStatus == "RUNNING", onClick = { onServiceAction(MockLocationService.ACTION_PAUSE, null) }) { Text("暂停") }
                            OutlinedButton(enabled = serviceStatus == "PAUSED", onClick = { onServiceAction(MockLocationService.ACTION_RESUME, null) }) { Text("继续") }
                            OutlinedButton(enabled = serviceStatus !in setOf("IDLE", "STOPPED"), onClick = { onServiceAction(MockLocationService.ACTION_STOP, null) }) { Text("停止") }
                        }
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("摇杆移动", style = MaterialTheme.typography.titleMedium)
                        Text("步长：${moveMeters.roundToInt()} 米", style = MaterialTheme.typography.bodySmall)
                        androidx.compose.material3.Slider(value = moveMeters, onValueChange = { moveMeters = it }, valueRange = 1f..100f)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { point?.let { applyPoint(it.moved(0.0, moveMeters.toDouble())) } }) { Text("北", fontSize = 16.sp) }
                            Spacer(Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            IconButton(onClick = { point?.let { applyPoint(it.moved(-moveMeters.toDouble(), 0.0)) } }) { Text("西", fontSize = 16.sp) }
                            Button(onClick = { point?.let { applyPoint(it.moved(0.0, 0.0)) } }) { Text("定位点") }
                            IconButton(onClick = { point?.let { applyPoint(it.moved(moveMeters.toDouble(), 0.0)) } }) { Text("东", fontSize = 16.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { point?.let { applyPoint(it.moved(0.0, -moveMeters.toDouble())) } }) { Text("南", fontSize = 16.sp) }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("地点搜索", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(query, { query = it }, label = { Text("搜索地点") }, modifier = Modifier.weight(1f), singleLine = true)
                            Button(enabled = query.trim().length >= 2 && !searching, onClick = {
                                searching = true; searchError = null
                                scope.launch(Dispatchers.IO) {
                                    val response = runCatching { searchPlaces(query) }.getOrElse { emptyList<SearchResult>() }
                                    withContext(Dispatchers.Main) {
                                        if (response.isEmpty()) searchError = "没有找到结果或搜索服务不可用"
                                        results = response
                                        searching = false
                                    }
                                }
                            }) { Text(if (searching) "搜索中" else "搜索") }
                        }
                        Text("搜索数据 © OpenStreetMap contributors · Nominatim", style = MaterialTheme.typography.labelSmall)
                        searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        results.forEach { result ->
                            TextButton(
                                onClick = { applyPoint(result.point); results = emptyList() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(result.name) }
                        }
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("历史位置", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(name, { name = it }, label = { Text("名称（可选）") }, modifier = Modifier.weight(1f), singleLine = true)
                            Button(enabled = point != null, onClick = { point?.let { history = store.save(name, it); name = "" } }) { Text("保存") }
                        }
                        if (history.isEmpty()) Text("暂无历史位置", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            itemsIndexed(history, key = { index, item -> "${item.name}-$index" }) { index, item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name)
                        Text("${item.point.latitude}, ${item.point.longitude}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { applyPoint(item.point) }) { Text("载入") }
                    TextButton(onClick = { history = store.remove(index) }) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, modifier = modifier, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

private fun parseCoordinate(value: String): Double = value.trim().replace(',', '.').toDouble()

private data class TileImage(val bitmap: android.graphics.Bitmap, val x: Int, val y: Int)

@Composable
private fun CoordinateMap(point: PositionPoint?, route: List<PositionPoint>, onTap: (Double, Double) -> Unit) {
    val context = LocalContext.current
    val center = point ?: PositionPoint(39.9042, 116.4074)
    val routeColor = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.error
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by rememberSaveable { mutableStateOf(14) }
    var tiles by remember { mutableStateOf(emptyList<TileImage>()) }
    LaunchedEffect(center, zoom, mapSize) {
        if (mapSize == IntSize.Zero) return@LaunchedEffect
        tiles = emptyList()
        kotlinx.coroutines.delay(450)
        val cx = MapTiles.x(center.longitude, zoom)
        val cy = MapTiles.y(center.latitude, zoom)
        val halfX = mapSize.width / 512.0
        val halfY = mapSize.height / 512.0
        val xs = (floor(cx - halfX).toInt()..floor(cx + halfX).toInt())
        val ys = (floor(cy - halfY).toInt()..floor(cy + halfY).toInt())
        val loaded = withContext(Dispatchers.IO) {
            xs.flatMap { x -> ys.mapNotNull { y -> MapTiles.fetch(context, x, y, zoom)?.let { TileImage(it, x, y) } } }
        }
        tiles = loaded
    }
    Column {
        Box(Modifier.fillMaxWidth().height(230.dp).background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { mapSize = it }
            .pointerInput(center, zoom) {
                detectTapGestures { offset ->
                    val tx = MapTiles.x(center.longitude, zoom) + (offset.x - size.width / 2f) / 256.0
                    val ty = MapTiles.y(center.latitude, zoom) + (offset.y - size.height / 2f) / 256.0
                    val lat = MapTiles.lat(ty, zoom)
                    val lon = ((MapTiles.lon(tx, zoom) + 180) % 360 + 360) % 360 - 180
                    onTap(lat, lon)
                }
            }) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = MapTiles.x(center.longitude, zoom)
                val cy = MapTiles.y(center.latitude, zoom)
                tiles.forEach { tile ->
                    val left = (size.width / 2 + (tile.x - cx) * 256).roundToInt()
                    val top = (size.height / 2 + (tile.y - cy) * 256).roundToInt()
                    drawImage(tile.bitmap.asImageBitmap(), topLeft = Offset(left.toFloat(), top.toFloat()))
                }
                if (tiles.isEmpty()) {
                    val grid = Color.Gray.copy(alpha = 0.25f)
                    for (i in 1..5) {
                        drawLine(grid, Offset(size.width * i / 6f, 0f), Offset(size.width * i / 6f, size.height))
                        drawLine(grid, Offset(0f, size.height * i / 6f), Offset(size.width, size.height * i / 6f))
                    }
                }
                fun project(item: PositionPoint): Offset = Offset(
                    (size.width / 2 + (MapTiles.x(item.longitude, zoom) - cx) * 256).toFloat(),
                    (size.height / 2 + (MapTiles.y(item.latitude, zoom) - cy) * 256).toFloat(),
                )
                if (route.size > 1) {
                    val path = Path().apply {
                        moveTo(project(route.first()).x, project(route.first()).y)
                        route.drop(1).forEach { lineTo(project(it).x, project(it).y) }
                    }
                    drawPath(path, routeColor, style = Stroke(width = 4f))
                }
                drawCircle(markerColor, radius = 8f, center = project(center))
            }
            Text("© OpenStreetMap contributors", Modifier.align(Alignment.BottomStart)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).padding(4.dp),
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("点击地图选点 · z$zoom", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextButton(enabled = zoom > 3, onClick = { zoom-- }) { Text("−") }
            TextButton(enabled = zoom < 18, onClick = { zoom++ }) { Text("+") }
        }
    }
}

private data class SearchResult(val name: String, val point: PositionPoint)

private var lastSearchAtMs = 0L

@Synchronized
private fun searchPlaces(query: String): List<SearchResult> {
    val now = System.currentTimeMillis()
    check(now - lastSearchAtMs >= 1_000L) { "搜索请求过于频繁，请稍后再试" }
    lastSearchAtMs = now
    val encoded = URLEncoder.encode(query.trim().take(80), "UTF-8")
    val connection = (URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=$encoded").openConnection() as HttpURLConnection).apply {
        connectTimeout = 6_000; readTimeout = 8_000; requestMethod = "GET"
        setRequestProperty("User-Agent", "RateMock/0.1 (+https://github.com/lxhtt/SchoolRunningSimulator)")
    }
    return try {
        if (connection.responseCode !in 200..299) error("搜索服务返回 ${connection.responseCode}")
        val payload = connection.inputStream.use { stream ->
            val buffer = ByteArray(8_192)
            val output = java.io.ByteArrayOutputStream()
            while (output.size() <= 100_000) {
                val count = stream.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            check(output.size() <= 100_000) { "搜索响应过大" }
            output.toString(Charsets.UTF_8.name())
        }
        val array = JSONArray(payload)
        (0 until min(array.length(), 5)).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            SearchResult(item.optString("display_name").take(160), PositionPoint(item.getDouble("lat"), item.getDouble("lon")))
        }.getOrNull() }
    } finally {
        connection.disconnect()
    }
}
