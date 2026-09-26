package dev.ratemock.app.position

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

internal object MapTiles {
    private val cache = object : LinkedHashMap<String, Bitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 48
    }

    fun x(longitude: Double, zoom: Int): Double = (longitude + 180.0) / 360.0 * (1 shl zoom)
    fun y(latitude: Double, zoom: Int): Double {
        val radians = Math.toRadians(latitude.coerceIn(-85.0511, 85.0511))
        return (1 - ln(tan(radians) + 1 / kotlin.math.cos(radians)) / PI) / 2 * (1 shl zoom)
    }
    fun lon(x: Double, zoom: Int): Double = x / (1 shl zoom) * 360 - 180
    fun lat(y: Double, zoom: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl zoom)))))

    fun fetch(context: Context, x: Int, y: Int, zoom: Int): Bitmap? {
        val width = 1 shl zoom
        if (y !in 0 until width) return null
        val wrappedX = ((x % width) + width) % width
        val key = "$zoom/$wrappedX/$y"
        synchronized(cache) { cache[key]?.let { return it } }
        val directory = File(context.cacheDir, "map_tiles")
        val disk = File(directory, "$zoom-$wrappedX-$y.png")
        val cacheAge = System.currentTimeMillis() - disk.lastModified()
        if (disk.isFile && cacheAge >= 0L && cacheAge <= 604_800_000L) {
            BitmapFactory.decodeFile(disk.path)?.let { bitmap ->
                synchronized(cache) { cache[key] = bitmap }
                return bitmap
            }
        }
        return try {
            val connection = (URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection).apply {
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
