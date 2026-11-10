package app.ipatlas.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Settings and History, kept on the phone (and in the phone's backups). */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ipatlas", Context.MODE_PRIVATE)

    var settings by mutableStateOf(readSettings())
        private set

    var history by mutableStateOf(readHistory())
        private set

    fun updateSettings(change: (Settings) -> Settings) {
        settings = change(settings)
        prefs.edit()
            .putString(K_THEME, settings.theme.key)
            .putString(K_SOURCE, settings.source?.key ?: "auto")
            .putBoolean(K_SAVE, settings.saveHistory)
            .apply()
    }

    private fun same(a: String, b: String) = a.equals(b, ignoreCase = true)

    /** Adds a lookup to the top of History (or moves it there), keeping its star. */
    fun record(entry: HistoryEntry) {
        val old = history.firstOrNull { same(it.query, entry.query) }
        if (!settings.saveHistory && old?.starred != true) return
        val updated = entry.copy(starred = old?.starred ?: false)
        history = (listOf(updated) + history.filterNot { same(it.query, entry.query) }).take(MAX)
        saveHistory()
    }

    fun isStarred(query: String): Boolean = history.any { same(it.query, query) && it.starred }

    fun setStarred(entry: HistoryEntry, starred: Boolean) {
        val exists = history.any { same(it.query, entry.query) }
        history = when {
            exists && !starred && !settings.saveHistory -> history.filterNot { same(it.query, entry.query) }
            exists -> history.map { if (same(it.query, entry.query)) it.copy(starred = starred) else it }
            starred -> listOf(entry.copy(starred = true)) + history
            else -> history
        }
        saveHistory()
    }

    fun delete(query: String) {
        history = history.filterNot { same(it.query, query) }
        saveHistory()
    }

    fun clear(keepStarred: Boolean) {
        history = if (keepStarred) history.filter { it.starred } else emptyList()
        saveHistory()
    }

    private fun readSettings(): Settings {
        val sourceKey = prefs.getString(K_SOURCE, "auto")
        return Settings(
            theme = ThemeMode.fromKey(prefs.getString(K_THEME, null)),
            source = if (sourceKey == "auto") null else Source.fromKey(sourceKey),
            saveHistory = prefs.getBoolean(K_SAVE, true),
        )
    }

    private fun readHistory(): List<HistoryEntry> {
        val raw = prefs.getString(K_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val query = o.str("q") ?: return@mapNotNull null
                HistoryEntry(
                    query = query,
                    ip = o.str("ip") ?: "",
                    place = o.str("place") ?: "",
                    countryCode = o.str("cc"),
                    provider = o.str("isp"),
                    at = o.optLong("at"),
                    starred = o.optBoolean("star"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistory() {
        val arr = JSONArray()
        for (h in history) {
            val o = JSONObject()
            o.put("q", h.query)
            o.put("ip", h.ip)
            o.put("place", h.place)
            if (h.countryCode != null) o.put("cc", h.countryCode)
            if (h.provider != null) o.put("isp", h.provider)
            o.put("at", h.at)
            o.put("star", h.starred)
            arr.put(o)
        }
        prefs.edit().putString(K_HISTORY, arr.toString()).apply()
    }

    private companion object {
        const val MAX = 250
        const val K_THEME = "theme"
        const val K_SOURCE = "source"
        const val K_SAVE = "save_history"
        const val K_HISTORY = "history"
    }
}
