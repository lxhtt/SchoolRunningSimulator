package dev.ratemock.app.position

import android.content.Context
import dev.ratemock.core.position.PositionPoint
import org.json.JSONArray
import org.json.JSONObject

/** Only explicitly selected synthetic points are stored; never imports recorder GPS. */
internal class PositionHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("position_history", Context.MODE_PRIVATE)

    fun entries(): List<Entry> = runCatching {
        val array = JSONArray(prefs.getString("entries", "[]"))
        (0 until minOf(array.length(), LIMIT)).mapNotNull { index ->
            runCatching {
                val obj = array.getJSONObject(index)
                Entry(obj.getString("name"), PositionPoint(obj.getDouble("lat"), obj.getDouble("lon"), obj.getDouble("alt")))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun save(name: String, point: PositionPoint): List<Entry> {
        val label = name.trim().take(60).ifBlank { "${point.latitude}, ${point.longitude}" }
        val updated = (listOf(Entry(label, point)) + entries().filterNot { it.point == point }).take(LIMIT)
        persist(updated)
        return updated
    }

    fun remove(index: Int): List<Entry> {
        val updated = entries().toMutableList()
        if (index in updated.indices) updated.removeAt(index)
        persist(updated)
        return updated
    }

    private fun persist(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { item ->
            array.put(JSONObject().put("name", item.name).put("lat", item.point.latitude)
                .put("lon", item.point.longitude).put("alt", item.point.altitude))
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    data class Entry(val name: String, val point: PositionPoint)
    companion object { private const val LIMIT = 50 }
}
