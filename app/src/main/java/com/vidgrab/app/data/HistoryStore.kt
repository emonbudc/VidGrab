package com.vidgrab.app.data

import android.content.Context
import com.vidgrab.app.model.HistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object HistoryStore {
    private const val PREFS = "vidgrab_history"
    private const val KEY = "entries"

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    @Synchronized
    fun load(context: Context) {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return
        _entries.value = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    add(array.getJSONObject(i).toEntry())
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(context: Context, entry: HistoryEntry) {
        val updated = listOf(entry) + _entries.value.filterNot { it.id == entry.id }
        _entries.value = updated
        save(context, updated)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val updated = _entries.value.filterNot { it.id == id }
        _entries.value = updated
        save(context, updated)
    }

    @Synchronized
    fun clear(context: Context) {
        _entries.value = emptyList()
        save(context, emptyList())
    }

    private fun save(context: Context, entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    private fun HistoryEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("url", url)
        put("fileName", fileName)
        put("uri", uri)
        put("mimeType", mimeType)
        put("sizeBytes", sizeBytes)
        put("timestamp", timestamp)
        put("quality", quality)
    }

    private fun JSONObject.toEntry(): HistoryEntry = HistoryEntry(
        id = optString("id"),
        title = optString("title"),
        url = optString("url"),
        fileName = optString("fileName"),
        uri = optString("uri"),
        mimeType = optString("mimeType"),
        sizeBytes = optLong("sizeBytes"),
        timestamp = optLong("timestamp"),
        quality = optString("quality")
    )
}
