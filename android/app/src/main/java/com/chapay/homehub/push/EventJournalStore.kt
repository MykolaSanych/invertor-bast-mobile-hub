package com.chapay.homehub.push

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class EventJournalEntry(
    val atMs: Long,
    val title: String,
    val body: String,
)

object EventJournalStore {
    private const val PREFS = "home_hub_event_journal"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 300
    private val lock = Any()

    fun append(context: Context, title: String, body: String, atMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val entries = loadEntriesUnsafe(context)
            entries.add(
                EventJournalEntry(
                    atMs = atMs,
                    title = title.trim(),
                    body = body.trim(),
                ),
            )
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
            saveEntriesUnsafe(context, entries)
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ENTRIES)
                .apply()
        }
    }

    fun toJson(context: Context, limit: Int = 200): JSONObject {
        synchronized(lock) {
            val entries = loadEntriesUnsafe(context)
            val safeLimit = limit.coerceIn(1, MAX_ENTRIES)
            val from = (entries.size - safeLimit).coerceAtLeast(0)
            val slice = entries.subList(from, entries.size)
            val items = JSONArray()
            for (i in slice.indices.reversed()) {
                val entry = slice[i]
                items.put(
                    JSONObject().apply {
                        put("atMs", entry.atMs)
                        put("title", entry.title)
                        put("body", entry.body)
                    },
                )
            }
            return JSONObject().apply {
                put("items", items)
                put("count", items.length())
            }
        }
    }

    private fun loadEntriesUnsafe(context: Context): MutableList<EventJournalEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
            ?: return mutableListOf()

        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return mutableListOf()
        val out = mutableListOf<EventJournalEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val atMs = obj.optLong("atMs", 0L)
            val title = obj.optString("title", "").trim()
            val body = obj.optString("body", "").trim()
            if (atMs <= 0L || title.isEmpty()) continue
            out.add(EventJournalEntry(atMs, title, body))
        }
        return out
    }

    private fun saveEntriesUnsafe(context: Context, entries: List<EventJournalEntry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("atMs", entry.atMs)
                    put("title", entry.title)
                    put("body", entry.body)
                },
            )
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, arr.toString())
            .apply()
    }
}
