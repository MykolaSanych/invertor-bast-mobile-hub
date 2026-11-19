package com.chapay.homehub.compose.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "sirio_event_log"
private const val KEY_EVENTS = "events"
private const val MAX_EVENTS = 200

data class SirioLogEvent(val tsMs: Long, val text: String, val bad: Boolean)

/**
 * Клієнтський журнал подій насоса Sirio (пуск/стоп, поява/зняття аварії, зміна
 * уставки) - сам пристрій не віддає журнал із часовими метками (лише
 * лічильники e0..e13), тож, як і в рідній веб-морді Sirio, журнал веде сам
 * клієнт. Спільний для екрана (показує список) і фонової служби
 * (дописує події навіть коли екран не відкритий).
 */
object SirioEventLogStore {
    @Synchronized
    fun add(context: Context, text: String, bad: Boolean = false) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = readAll(p).toMutableList()
        events.add(0, SirioLogEvent(System.currentTimeMillis(), text, bad))
        while (events.size > MAX_EVENTS) events.removeAt(events.size - 1)
        writeAll(p, events)
    }

    fun recent(context: Context): List<SirioLogEvent> =
        readAll(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private fun readAll(p: android.content.SharedPreferences): List<SirioLogEvent> {
        val raw = p.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SirioLogEvent(o.optLong("t"), o.optString("text"), o.optBoolean("bad", false))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(p: android.content.SharedPreferences, events: List<SirioLogEvent>) {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(JSONObject().put("t", e.tsMs).put("text", e.text).put("bad", e.bad))
        }
        p.edit().putString(KEY_EVENTS, arr.toString()).apply()
    }
}
