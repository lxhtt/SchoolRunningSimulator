package dev.ratemock.app.position

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.ratemock.core.position.PositionPoint
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.sinh
import kotlin.math.tan

internal object MapTiles {
    private val cache = object : LinkedHashMap<String, Bitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 48
    }

    data class DisplayCoordinate(val latitude: Double, val longitude: Double)

    fun toDisplay(point: PositionPoint): DisplayCoordinate = toGcj02(point.latitude, point.longitude)

    fun fromDisplay(latitude: Double, longitude: Double): DisplayCoordinate {
        if (!inChina(latitude, longitude)) return DisplayCoordinate(latitude, longitude)
        var candidate = DisplayCoordinate(latitude, longitude)
        repeat(6) {
            val projected = toGcj02(candidate.latitude, candidate.longitude)
            candidate = DisplayCoordinate(
                latitude + (latitude - projected.latitude),
                longitude + (longitude - projected.longitude),
            )
        }
        return candidate
    }

    private fun toGcj02(latitude: Double, longitude: Double): DisplayCoordinate {
        if (!inChina(latitude, longitude)) return DisplayCoordinate(latitude, longitude)
        val delta = transform(latitude, longitude)
        return DisplayCoordinate(latitude + delta.latitude, longitude + delta.longitude)
    }

    private fun transform(latitude: Double, longitude: Double): DisplayCoordinate {
        val dLat = transformLatitude(longitude - 105.0, latitude - 35.0)
        val dLon = transformLongitude(longitude - 105.0, latitude - 35.0)
        val radLat = latitude / 180.0 * PI
        val magic = 1 - 0.006693421622965943 * sin(radLat) * sin(radLat)
        val sqrtMagic = sqrt(magic)
        return DisplayCoordinate(
            dLat * 180.0 / (6335552.717000426 / (magic * sqrtMagic) * PI),
            dLon * 180.0 / (6378245.0 / sqrtMagic * cos(radLat) * PI),
        )
    }

    private fun transformLatitude(x: Double, y: Double): Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return result
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return result
    }

    private fun inChina(latitude: Double, longitude: Double): Boolean =
        longitude in 72.004..137.8347 && latitude in 0.8293..55.8271

    fun x(longitude: Double, zoom: Int): Double = (longitude + 180.0) / 360.0 * (1 shl zoom)
    fun x(longitude: Double, zoom: Float): Double = (longitude + 180.0) / 360.0 * 2.0.pow(zoom.toDouble())

    fun y(latitude: Double, zoom: Int): Double {
        val radians = Math.toRadians(latitude.coerceIn(-85.0511, 85.0511))
        return (1 - ln(tan(radians) + 1 / kotlin.math.cos(radians)) / PI) / 2 * (1 shl zoom)
    }
    fun y(latitude: Double, zoom: Float): Double {
        val radians = Math.toRadians(latitude.coerceIn(-85.0511, 85.0511))
        return (1 - ln(tan(radians) + 1 / kotlin.math.cos(radians)) / PI) / 2 * 2.0.pow(zoom.toDouble())
    }
    fun lon(x: Double, zoom: Int): Double = x / (1 shl zoom) * 360 - 180
    fun lat(y: Double, zoom: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl zoom)))))
    fun lonAtScale(x: Double, scale: Double): Double = x / scale * 360.0 - 180.0
    fun latAtScale(y: Double, scale: Double): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / scale))))

    fun fetch(context: Context, x: Int, y: Int, zoom: Int): Bitmap? {
        val width = 1 shl zoom
        if (y !in 0 until width) return null
        val wrappedX = ((x % width) + width) % width
        val key = "amap/$zoom/$wrappedX/$y"
        synchronized(cache) { cache[key]?.let { return it } }
        val directory = File(context.cacheDir, "amap_tiles")
        val disk = File(directory, "$zoom-$wrappedX-$y.png")
        val cacheAge = System.currentTimeMillis() - disk.lastModified()
        if (disk.isFile && cacheAge >= 0L && cacheAge <= 604_800_000L) {
            BitmapFactory.decodeFile(disk.path)?.let { bitmap ->
                synchronized(cache) { cache[key] = bitmap }
                return bitmap
            }
        }
        return try {
            val connection = (URL("https://webrd0${(wrappedX + y + zoom) % 4 + 1}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x=$wrappedX&y=$y&z=$zoom").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", "RateMock/0.1 (+https://github.com/lxhtt/SchoolRunningSimulator)")
            }
            try {
                if (connection.responseCode != 200) return null
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 300_000) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    if (output.size() > 300_000) return null
                    output.toByteArray()
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { bitmap ->
                    synchronized(cache) { cache[key] = bitmap }
                    directory.mkdirs()
                    disk.writeBytes(bytes)
                    directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                        ?.drop(256)?.forEach { it.delete() }
                }
            } finally { connection.disconnect() }
        } catch (_: Exception) { null }
    }
}
