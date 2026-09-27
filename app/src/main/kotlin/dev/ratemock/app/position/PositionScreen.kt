package dev.ratemock.app.position

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ratemock.app.position.MockLocationService
import dev.ratemock.app.simulation.SimulatorService
import dev.ratemock.core.position.PositionPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PositionScreen(
    onRequestPermissions: () -> Unit,
    onOpenMockSettings: () -> Unit,
    onServiceAction: (String, PositionPoint?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { PositionHistoryStore(context) }
    var latitudeText by rememberSaveable { mutableStateOf("") }
    var longitudeText by rememberSaveable { mutableStateOf("") }
    var altitudeText by rememberSaveable { mutableStateOf("0") }
    var point by remember { mutableStateOf<PositionPoint?>(null) }
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
    var liveLocation by remember { mutableStateOf<PositionPoint?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var locationPermissionGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (granted != locationPermissionGranted) locationPermissionGranted = granted
            kotlinx.coroutines.delay(500)
        }
    }

    DisposableEffect(locationPermissionGranted) {
        if (!locationPermissionGranted) {
            locationMessage = "请先授予定位权限"
            onDispose { }
        } else {
            val client = runCatching { com.amap.api.location.AMapLocationClient(context.applicationContext) }.getOrNull()
            if (client == null) {
                locationMessage = "高德定位 SDK 初始化失败"
                onDispose { }
            } else {
                val option = com.amap.api.location.AMapLocationClientOption()
                    .setLocationMode(com.amap.api.location.AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
                    .setInterval(1_000L)
                    .setNeedAddress(false)
                    .setMockEnable(false)
                client.setLocationOption(option)
                client.setLocationListener { location ->
                    if (location != null && location.errorCode == 0 && !location.isMock) {
                        val wgs = MapTiles.fromDisplay(location.latitude, location.longitude)
                        liveLocation = PositionPoint(wgs.latitude, wgs.longitude, location.altitude)
                        locationMessage = "实时定位已更新"
                    } else if (location != null && location.errorCode != 0) {
                        locationMessage = "定位失败：${location.errorInfo ?: location.errorCode}"
                    }
                }
                client.startLocation()
                onDispose {
                    runCatching { client.stopLocation() }
                    runCatching { client.onDestroy() }
                }
            }
        }
    }

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
                Text("地图路线仅显示本次选点和摇杆轨迹；回放进度显示在服务状态中。地图使用高德栅格图层，应用坐标仍为 WGS84。", style = MaterialTheme.typography.bodySmall)
                AMapCoordinateMap(point, route, liveLocation, onTap = { lat, lon ->
                    runCatching { applyPoint(PositionPoint(lat, lon, point?.altitude ?: 0.0)) }
                }, onUseLiveLocation = {
                    if (liveLocation != null) liveLocation?.let { applyPoint(it) } else onRequestPermissions()
                }, onRouteDrawn = { drawnRoute ->
                    if (drawnRoute.isNotEmpty()) {
                        route = drawnRoute.takeLast(200)
                        drawnRoute.last().let {
                            point = it
                            latitudeText = it.latitude.toString()
                            longitudeText = it.longitude.toString()
                            altitudeText = it.altitude.toString()
                        }
                    }
                }, hasLiveLocation = liveLocation != null)
                Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        locationMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                                    val response = runCatching { searchPlaces(context, query) }.getOrElse { emptyList<SearchResult>() }
                                    withContext(Dispatchers.Main) {
                                        if (response.isEmpty()) searchError = "没有找到结果或搜索服务不可用"
                                        results = response
                                        searching = false
                                    }
                                }
                            }) { Text(if (searching) "搜索中" else "搜索") }
                        }
                        Text("搜索数据 © 高德地图", style = MaterialTheme.typography.labelSmall)
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


private data class SearchResult(val name: String, val point: PositionPoint)

private var lastSearchAtMs = 0L

@Synchronized
private fun searchPlaces(context: android.content.Context, query: String): List<SearchResult> {
    val now = System.currentTimeMillis()
    check(now - lastSearchAtMs >= 1_000L) { "搜索请求过于频繁，请稍后再试" }
    lastSearchAtMs = now
    val latch = java.util.concurrent.CountDownLatch(1)
    var output: List<SearchResult> = emptyList()
    var failure: String? = null
    try {
        val searchQuery = com.amap.api.services.poisearch.PoiSearch.Query(query.trim().take(80), "", "")
        searchQuery.pageSize = 5
        val search = com.amap.api.services.poisearch.PoiSearch(context.applicationContext, searchQuery)
        search.setOnPoiSearchListener(object : com.amap.api.services.poisearch.PoiSearch.OnPoiSearchListener {
            override fun onPoiSearched(result: com.amap.api.services.poisearch.PoiResult?, code: Int) {
                if (code == 1000 && result != null) {
                    output = result.pois.orEmpty().take(5).mapNotNull { item ->
                        val location = item.latLonPoint ?: return@mapNotNull null
                        val wgs = MapTiles.fromDisplay(location.latitude, location.longitude)
                        SearchResult(item.title.orEmpty().take(160), PositionPoint(wgs.latitude, wgs.longitude))
                    }
                } else failure = "高德搜索失败（$code）"
                latch.countDown()
            }
            override fun onPoiItemSearched(item: com.amap.api.services.core.PoiItem?, code: Int) = Unit
        })
        search.searchPOIAsyn()
        check(latch.await(12, java.util.concurrent.TimeUnit.SECONDS)) { "高德搜索超时" }
        failure?.let { error(it) }
        return output
    } catch (error: Exception) {
        throw IllegalStateException(error.message ?: "高德搜索不可用", error)
    }
}
