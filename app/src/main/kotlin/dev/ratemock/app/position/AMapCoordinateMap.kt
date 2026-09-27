package dev.ratemock.app.position

import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import dev.ratemock.core.position.PositionPoint

@Composable
internal fun AMapCoordinateMap(
    point: PositionPoint?,
    route: List<PositionPoint>,
    liveLocation: PositionPoint?,
    onTap: (Double, Double) -> Unit,
    onUseLiveLocation: () -> Unit,
    onRouteDrawn: (List<PositionPoint>) -> Unit,
    hasLiveLocation: Boolean,
) {
    val context = LocalContext.current
    val initial = remember { point ?: liveLocation ?: PositionPoint(39.9042, 116.4074) }
    val mapView = remember(context) { MapView(context).also { it.onCreate(Bundle()) } }
    var map by remember { mutableStateOf<AMap?>(null) }
    var drawing by rememberSaveable { mutableStateOf(false) }
    var draftRoute by remember { mutableStateOf(emptyList<PositionPoint>()) }
    var selectedMarker by remember { mutableStateOf<Marker?>(null) }
    var liveMarker by remember { mutableStateOf<Marker?>(null) }
    var routeLine by remember { mutableStateOf<Polyline?>(null) }
    var draftLine by remember { mutableStateOf<Polyline?>(null) }
    val drawingState = rememberUpdatedState(drawing)
    val onTapState = rememberUpdatedState(onTap)
    val onRouteDrawnState = rememberUpdatedState(onRouteDrawn)

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onSaveInstanceState(Bundle())
            mapView.onDestroy()
        }
    }
    LaunchedEffect(map, drawing) {
        val aMap = map ?: return@LaunchedEffect
        aMap.uiSettings.isScrollGesturesEnabled = !drawing
        aMap.uiSettings.isZoomGesturesEnabled = !drawing
        aMap.uiSettings.isRotateGesturesEnabled = !drawing
        aMap.setOnMapClickListener { latLng ->
            if (!drawingState.value) {
                val wgs = latLng.toWgs84()
                onTapState.value(wgs.latitude, wgs.longitude)
            }
        }
        aMap.setOnMapTouchListener { event ->
            if (!drawingState.value) return@setOnMapTouchListener
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draftRoute = mapPoint(event.x, event.y, aMap)?.let(::listOf).orEmpty()
                    draftLine?.remove()
                    draftLine = null
                }
                MotionEvent.ACTION_MOVE -> {
                    mapPoint(event.x, event.y, aMap)?.let { next ->
                        if (draftRoute.lastOrNull()?.distanceTo(next)?.let { it >= 2.0 } != false) {
                            draftRoute = (draftRoute + next).takeLast(200)
                            draftLine?.remove()
                            draftLine = aMap.addPolyline(polylineOptions(draftRoute, Color.rgb(255, 152, 0), 8f))
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP && draftRoute.size > 1) {
                        onRouteDrawnState.value(draftRoute)
                    }
                    draftRoute = emptyList()
                    draftLine?.remove()
                    draftLine = null
                }
            }
        }
    }
    LaunchedEffect(map, point, liveLocation, route) {
        val aMap = map ?: return@LaunchedEffect
        selectedMarker?.remove()
        selectedMarker = point?.let { aMap.addMarker(MarkerOptions().position(it.toAMapLatLng()).title("模拟位置")) }
        liveMarker?.remove()
        liveMarker = liveLocation?.let {
            aMap.addMarker(
                MarkerOptions().position(it.toAMapLatLng()).title("实时位置")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)),
            )
        }
        routeLine?.remove()
        routeLine = if (route.size > 1) {
            aMap.addPolyline(polylineOptions(route, Color.rgb(33, 150, 243), 10f))
        } else {
            null
        }
    }
    Column {
        Box(Modifier.fillMaxWidth().height(360.dp)) {
            AndroidView(
                factory = { mapView },
                update = { view -> if (map == null) map = view.getMap() },
                modifier = Modifier.fillMaxWidth().height(360.dp),
            )
            TextButton(
                onClick = {
                    drawing = !drawing
                    if (!drawing) {
                        draftRoute = emptyList()
                        draftLine?.remove()
                        draftLine = null
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .92f)),
            ) { Text(if (drawing) "完成画线" else "画轨迹") }
            TextButton(
                onClick = onUseLiveLocation,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .92f)),
            ) { Text(if (hasLiveLocation) "我的位置" else "开启定位") }
            Text(
                "© 高德地图",
                Modifier.align(Alignment.BottomStart)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .85f)).padding(4.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (drawing) "正在画线：拖动手指绘制路线" else "轻触选点 · 拖动地图 · 双指缩放 · 原生地图手势")
        }
    }
}

private fun polylineOptions(points: List<PositionPoint>, color: Int, width: Float): PolylineOptions =
    PolylineOptions().addAll(points.map { it.toAMapLatLng() }).color(color).width(width).geodesic(true)

private fun mapPoint(x: Float, y: Float, map: AMap): PositionPoint? =
    map.projection?.fromScreenLocation(android.graphics.Point(x.toInt(), y.toInt()))?.toWgs84()

private fun PositionPoint.toAMapLatLng(): LatLng {
    val display = MapTiles.toDisplay(this)
    return LatLng(display.latitude, display.longitude)
}

private fun LatLng.toWgs84(): PositionPoint {
    val wgs = MapTiles.fromDisplay(latitude, longitude)
    return PositionPoint(wgs.latitude, wgs.longitude)
}
