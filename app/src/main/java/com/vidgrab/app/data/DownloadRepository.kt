package com.vidgrab.app.data

import android.content.Context
import com.vidgrab.app.model.DownloadTask
import com.vidgrab.app.model.Quality
import com.vidgrab.app.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object DownloadRepository {
    private const val PREFS = "vidgrab_tasks"
    private const val KEY = "tasks"

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    @Synchronized
    fun load(context: Context) {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    add(array.getJSONObject(i).toTask())
                }
            }
        }.getOrDefault(emptyList())

        val reconciled = parsed.map { task ->
            if (task.status == TaskStatus.QUEUED ||
                task.status == TaskStatus.PREPARING ||
                task.status == TaskStatus.DOWNLOADING
            ) {
                task.copy(status = TaskStatus.PAUSED, etaSeconds = 0L)
            } else {
                task
            }
        }
        _tasks.value = reconciled
        save(context, reconciled)
    }

    @Synchronized
    fun enqueue(context: Context, url: String, title: String?, quality: Quality): DownloadTask {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title,
            quality = quality,
            status = TaskStatus.QUEUED
        )
        val updated = _tasks.value + task
        _tasks.value = updated
        save(context, updated)
        return task
    }

    @Synchronized
    fun update(context: Context, id: String, persist: Boolean = false, transform: (DownloadTask) -> DownloadTask) {
        val updated = _tasks.value.map { task -> if (task.id == id) transform(task) else task }
        _tasks.value = updated
        if (persist) save(context, updated)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val updated = _tasks.value.filterNot { it.id == id }
        _tasks.value = updated
        save(context, updated)
    }

    @Synchronized
    fun claimNext(context: Context): DownloadTask? {
        val next = _tasks.value.firstOrNull { it.status == TaskStatus.QUEUED } ?: return null
        val claimed = next.copy(status = TaskStatus.PREPARING, progress = 0f, etaSeconds = 0L, error = null)
        val updated = _tasks.value.map { if (it.id == claimed.id) claimed else it }
        _tasks.value = updated
        save(context, updated)
        return claimed
    }

    fun find(id: String?): DownloadTask? {
        if (id == null) return null
        return _tasks.value.firstOrNull { it.id == id }
    }

    @Synchronized
    fun clearFinished(context: Context) {
        val updated = _tasks.value.filter { it.status != TaskStatus.FAILED }
        _tasks.value = updated
        save(context, updated)
    }

    private fun save(context: Context, tasks: List<DownloadTask>) {
        val array = JSONArray()
        tasks.forEach { array.put(it.toJson()) }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    private fun DownloadTask.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("title", title ?: "")
        put("quality", quality.name)
        put("status", status.name)
        put("progress", progress.toDouble())
        put("etaSeconds", etaSeconds)
        put("error", error ?: "")
        put("createdAt", createdAt)
    }

    private fun JSONObject.toTask(): DownloadTask {
        val quality = Quality.entries.firstOrNull { it.name == optString("quality") } ?: Quality.BEST
        val status = TaskStatus.entries.firstOrNull { it.name == optString("status") } ?: TaskStatus.PAUSED
        return DownloadTask(
            id = optString("id"),
            url = optString("url"),
            title = optString("title").takeIf { it.isNotBlank() },
            quality = quality,
            status = status,
            progress = optDouble("progress", 0.0).toFloat(),
            etaSeconds = optLong("etaSeconds"),
            error = optString("error").takeIf { it.isNotBlank() },
            createdAt = optLong("createdAt")
        )
    }
}
