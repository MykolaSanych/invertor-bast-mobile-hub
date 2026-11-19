package com.chapay.homehub.compose.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "voltage_local_history"
private const val KEY_SAMPLES = "samples"
private const val MIN_RECORD_INTERVAL_MS = 5 * 60 * 1000L
private const val RETENTION_MS = 24L * 3600 * 1000
// Запас понад 24год/5хв=288, щоб не різало дані, коли опитування трохи частіше.
private const val MAX_SAMPLES = 320

data class LocalVoltageSample(val tsMs: Long, val grid: Double, val pv: Double, val battery: Double)

/**
 * Власна 24-годинна історія напруг НА ТЕЛЕФОНІ - окремо від буфера модуля.
 * Користувач явно попросив: завантажені дані варто тримати добу на телефоні,
 * бо модуль часто перезавантажується і може втратити частину своєї (навіть
 * файлової) історії між двома відкриттями графіка - це підстраховка, а не
 * заміна серверної історії. Джерело запису - звичайний періодичний опит
 * статусу (DashboardViewModel.fetchOnce), не окремий мережевий запит.
 */
object VoltageLocalHistoryStore {
    @Volatile
    private var lastRecordedMs = 0L

    // battery - SOC АКБ (%), не напруга: легітимно може бути малим (глибокий
    // розряд), тож перевіряємо ознаку зіпсованого знімка через мережу+сонце+
    // SOC одночасно РІВНО нуль, а не через саму лише цю величину.
    fun recordIfDue(context: Context, nowMs: Long, grid: Double, pv: Double, battery: Double) {
        if (nowMs - lastRecordedMs < MIN_RECORD_INTERVAL_MS) return
        if (grid <= 0.0 && pv <= 0.0 && battery <= 0.0) return
        lastRecordedMs = nowMs
        val prefs = prefs(context)
        val cutoff = nowMs - RETENTION_MS
        val samples = readSamples(prefs).filter { it.tsMs >= cutoff } + LocalVoltageSample(nowMs, grid, pv, battery)
        writeSamples(prefs, samples.takeLast(MAX_SAMPLES))
    }

    fun recentSamples(context: Context, nowMs: Long = System.currentTimeMillis()): List<LocalVoltageSample> {
        val cutoff = nowMs - RETENTION_MS
        return readSamples(prefs(context)).filter { it.tsMs >= cutoff }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readSamples(prefs: SharedPreferences): List<LocalVoltageSample> {
        val raw = prefs.getString(KEY_SAMPLES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                LocalVoltageSample(
                    tsMs = o.optLong("ts"),
                    grid = o.optDouble("grid"),
                    pv = o.optDouble("pv"),
                    battery = o.optDouble("battery"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeSamples(prefs: SharedPreferences, samples: List<LocalVoltageSample>) {
        val arr = JSONArray()
        samples.forEach { s ->
            arr.put(
                JSONObject()
                    .put("ts", s.tsMs)
                    .put("grid", s.grid)
                    .put("pv", s.pv)
                    .put("battery", s.battery),
            )
        }
        prefs.edit().putString(KEY_SAMPLES, arr.toString()).apply()
    }
}
