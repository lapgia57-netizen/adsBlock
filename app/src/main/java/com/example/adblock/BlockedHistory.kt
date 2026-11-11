package com.example.adblock

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

enum class BlockedSource(val displayName: String) {
    DNS("DNS"),
    BROWSER("Trình duyệt")
}

data class BlockedEntry(
    val domain: String,
    val timestamp: Long,
    val source: BlockedSource
)

object BlockedHistory {
    private const val PREFERENCES_NAME = "blocked_history"
    private const val ENTRIES_KEY = "entries"
    private const val MAX_ENTRIES = 2_000
    private const val TAG = "BlockedHistory"
    private val lock = Any()
    private var loaded = false

    val entries = MutableStateFlow<List<BlockedEntry>>(emptyList())
    val warning = MutableStateFlow("")

    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return

            val stored = preferences(context).getString(ENTRIES_KEY, null)
            if (stored != null) {
                try {
                    val json = JSONArray(stored)
                    val restored = buildList {
                        for (index in 0 until json.length()) {
                            val item = json.getJSONObject(index)
                            add(
                                BlockedEntry(
                                    domain = item.getString("domain"),
                                    timestamp = item.getLong("timestamp"),
                                    source = BlockedSource.valueOf(item.getString("source"))
                                )
                            )
                        }
                    }.take(MAX_ENTRIES)
                    entries.value = restored
                } catch (error: JSONException) {
                    warning.value = "Không đọc được lịch sử đã lưu. Lịch sử mới vẫn sẽ được ghi lại."
                    Log.e(TAG, "Stored blocked history is invalid", error)
                } catch (error: IllegalArgumentException) {
                    warning.value = "Lịch sử có dữ liệu không hợp lệ. Lịch sử mới vẫn sẽ được ghi lại."
                    Log.e(TAG, "Stored blocked history contains an unknown source", error)
                }
            }
            loaded = true
        }
    }

    fun record(context: Context, domain: String, source: BlockedSource) {
        val normalizedDomain = domain.lowercase().trim().trimEnd('.')
        if (normalizedDomain.isBlank()) return

        synchronized(lock) {
            if (!loaded) load(context)
            val updated = (listOf(BlockedEntry(normalizedDomain, System.currentTimeMillis(), source)) +
                entries.value).take(MAX_ENTRIES)
            entries.value = updated
            preferences(context).edit().putString(ENTRIES_KEY, toJson(updated)).apply()
        }
    }

    fun exportJson(items: List<BlockedEntry>): String {
        val json = JSONArray()
        items.forEach { entry ->
            json.put(
                JSONObject()
                    .put("domain", entry.domain)
                    .put("blockedAt", DateFormat.getDateTimeInstance().format(Date(entry.timestamp)))
                    .put("timestamp", entry.timestamp)
                    .put("source", entry.source.displayName)
            )
        }
        return json.toString(2)
    }

    fun exportText(items: List<BlockedEntry>): String = buildString {
        appendLine("Lịch sử website đã chặn")
        appendLine("Tổng số: ${items.size}")
        appendLine()
        items.forEach { entry ->
            append(DateFormat.getDateTimeInstance().format(Date(entry.timestamp)))
            append(" | ")
            append(entry.domain)
            append(" | ")
            appendLine(entry.source.displayName)
        }
    }

    private fun toJson(items: List<BlockedEntry>): String {
        val json = JSONArray()
        items.forEach { entry ->
            json.put(
                JSONObject()
                    .put("domain", entry.domain)
                    .put("timestamp", entry.timestamp)
                    .put("source", entry.source.name)
            )
        }
        return json.toString()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
