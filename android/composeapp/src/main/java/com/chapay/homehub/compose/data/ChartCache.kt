package com.chapay.homehub.compose.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Дисковий кеш даних графіків: добова/місячна/річна історія інвертора, хронологія й
 * журнал load_controller. Модулі (ESP32) віддають їх повільно - місяць 8 с, рік ~20 с
 * - і чим довша історія, тим гірше, тому закриті періоди (минулі доби/місяці/роки) ми
 * зберігаємо на телефоні й більше з мережі не тягнемо. Файл на запис: `{"t":мс,"p":{...}}`.
 *
 * Це ще й АРХІВ історії: закриті періоди на телефоні лишаються назавжди, автоматично нічого
 * не видаляється (модулі чистять свої найстаріші доби лише після підтвердження, що телефон
 * їх зберіг). Видалити можна тільки вручну в Налаштуваннях, із підтвердженням.
 */
class ChartCache(context: Context?) {
    class Entry(val payload: JSONObject, val savedAtMs: Long)

    private val dir: File? = context?.applicationContext?.let { File(it.filesDir, "chart_cache").apply { mkdirs() } }
    private val lock = Any()

    /** Стабільний короткий "відбиток" адреси модуля: кеш однієї установки не змішується з іншою. */
    fun scopeOf(url: String): String = Integer.toHexString(url.trim().lowercase().trimEnd('/').hashCode())

    private fun fileFor(kind: String, scope: String, key: String): File? {
        val safeKey = key.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return dir?.let { File(it, "${kind}_${scope}_$safeKey.json") }
    }

    fun get(kind: String, scope: String, key: String): Entry? {
        val file = fileFor(kind, scope, key) ?: return null
        return synchronized(lock) {
            runCatching {
                if (!file.isFile) return@runCatching null
                val wrapper = JSONObject(file.readText())
                Entry(wrapper.getJSONObject("p"), wrapper.getLong("t"))
            }.getOrNull()
        }
    }

    fun put(kind: String, scope: String, key: String, payload: JSONObject) {
        val file = fileFor(kind, scope, key) ?: return
        synchronized(lock) {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(JSONObject().put("t", System.currentTimeMillis()).put("p", payload).toString())
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    /** (кількість файлів, сумарний розмір у байтах). */
    fun stats(): Pair<Int, Long> {
        val files = dir?.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }

    fun clear(): Int {
        val files = dir?.listFiles() ?: return 0
        return synchronized(lock) { files.count { it.delete() } }
    }

    /** Ключі (дати/періоди) усіх записів цього виду й адреси модуля. */
    fun listKeys(kind: String, scope: String): List<String> {
        val prefix = "${kind}_${scope}_"
        val files = dir?.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".json") } ?: return emptyList()
        return files.map { it.name.removePrefix(prefix).removeSuffix(".json") }
    }
}
