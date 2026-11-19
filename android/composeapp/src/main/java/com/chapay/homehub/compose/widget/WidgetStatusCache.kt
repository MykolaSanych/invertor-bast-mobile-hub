package com.chapay.homehub.compose.widget

import android.content.Context
import android.util.Log
import com.chapay.homehub.compose.data.UnifiedStatus
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Персистентний (на диск) кеш останнього ДІЙСНОГО стану для віджетів.
 *
 * StatusWidgetProvider.lastGoodAutoStatus живе лише в пам'яті процесу. Коли
 * систему вбиває процес застосунку (Doze/нестача RAM - а застосунок здебільшого
 * закритий), цей кеш губиться. Якщо наступний холодний фоновий фетч не встигає
 * (єдиний маршрут до модулів - ZeroTier tun0, у Doze він відпадає), патчити
 * відсутні модулі немає чим, і віджет малює "--" замість останніх відомих
 * значень. Саме це й бачив користувач: "прочерки замість актуальної інформації".
 *
 * Зберігаємо знімок на диск, щоб холодний старт одразу відновив останні реальні
 * дані в lastGoodAutoStatus - тоді навіть при невдалому першому фетчі віджет
 * показує останні значення, а не прочерки.
 */
internal object WidgetStatusCache {
    private const val TAG = "HomeHubComposeWidget"
    private const val FILE_NAME = "widget_status_snapshot.bin"

    // Старіший знімок не відновлюємо: багатогодинна "заморожена" картинка гірша
    // за чесні прочерки (стан реле міг давно змінитись). Доба - бо саме стільки
    // спокійно переживає нічний Doze без жодного успішного фетчу.
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    fun save(context: Context, status: UnifiedStatus) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            ObjectOutputStream(file.outputStream().buffered()).use { it.writeObject(status) }
        }.onFailure { Log.w(TAG, "widget cache save failed: ${it.message}") }
    }

    fun load(context: Context): UnifiedStatus? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            val status = ObjectInputStream(file.inputStream().buffered()).use {
                it.readObject() as UnifiedStatus
            }
            // Несвіжий знімок свідомо відкидаємо (краще прочерки, ніж стара брехня).
            if (System.currentTimeMillis() - status.updatedAtMs > MAX_AGE_MS) null else status
        }.getOrElse {
            // Несумісний формат (змінились моделі в новій версії застосунку) або
            // пошкоджений файл - просто ігноруємо, кеш один раз репопулюється.
            Log.w(TAG, "widget cache load failed: ${it.message}")
            null
        }
    }
}
