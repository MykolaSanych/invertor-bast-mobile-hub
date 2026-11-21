package com.chapay.homehub.compose.data

import android.content.Context

/**
 * Позиції вузлів екрана "Схема" - у ЧАСТКАХ ширини/висоти (0..1, не в dp/px),
 * бо сам екран малює вузли саме так (незалежно від розміру екрана). Користувач
 * може перетягувати кожен вузол пальцем; позиція зберігається локально.
 */
data class SchemeLayout(
    val pvX: Float, val pvY: Float,
    val gridX: Float, val gridY: Float,
    val inverterX: Float, val inverterY: Float,
    val batteryX: Float, val batteryY: Float,
    val loadX: Float, val loadY: Float,
    val boiler1X: Float, val boiler1Y: Float,
    val pumpX: Float, val pumpY: Float,
    val boiler2X: Float, val boiler2Y: Float,
)

object SchemeLayoutStore {
    private const val PREFS = "home_hub_scheme_layout"

    // Початкове розташування - те саме, що було захардкоджене до появи
    // перетягування.
    val DEFAULT = SchemeLayout(
        pvX = 0.22f, pvY = 0.09f,
        gridX = 0.78f, gridY = 0.09f,
        inverterX = 0.5f, inverterY = 0.30f,
        batteryX = 0.22f, batteryY = 0.51f,
        loadX = 0.78f, loadY = 0.51f,
        boiler1X = 0.18f, boiler1Y = 0.74f,
        pumpX = 0.5f, pumpY = 0.74f,
        boiler2X = 0.82f, boiler2Y = 0.74f,
    )

    private fun key(id: String, axis: Char) = "${id}_$axis"

    private val ids = listOf("pv", "grid", "inverter", "battery", "load", "boiler1", "pump", "boiler2")

    fun load(context: Context): SchemeLayout {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun x(id: String, default: Float) = p.getFloat(key(id, 'x'), default)
        fun y(id: String, default: Float) = p.getFloat(key(id, 'y'), default)
        return SchemeLayout(
            pvX = x("pv", DEFAULT.pvX), pvY = y("pv", DEFAULT.pvY),
            gridX = x("grid", DEFAULT.gridX), gridY = y("grid", DEFAULT.gridY),
            inverterX = x("inverter", DEFAULT.inverterX), inverterY = y("inverter", DEFAULT.inverterY),
            batteryX = x("battery", DEFAULT.batteryX), batteryY = y("battery", DEFAULT.batteryY),
            loadX = x("load", DEFAULT.loadX), loadY = y("load", DEFAULT.loadY),
            boiler1X = x("boiler1", DEFAULT.boiler1X), boiler1Y = y("boiler1", DEFAULT.boiler1Y),
            pumpX = x("pump", DEFAULT.pumpX), pumpY = y("pump", DEFAULT.pumpY),
            boiler2X = x("boiler2", DEFAULT.boiler2X), boiler2Y = y("boiler2", DEFAULT.boiler2Y),
        )
    }

    fun save(context: Context, layout: SchemeLayout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(key("pv", 'x'), layout.pvX).putFloat(key("pv", 'y'), layout.pvY)
            .putFloat(key("grid", 'x'), layout.gridX).putFloat(key("grid", 'y'), layout.gridY)
            .putFloat(key("inverter", 'x'), layout.inverterX).putFloat(key("inverter", 'y'), layout.inverterY)
            .putFloat(key("battery", 'x'), layout.batteryX).putFloat(key("battery", 'y'), layout.batteryY)
            .putFloat(key("load", 'x'), layout.loadX).putFloat(key("load", 'y'), layout.loadY)
            .putFloat(key("boiler1", 'x'), layout.boiler1X).putFloat(key("boiler1", 'y'), layout.boiler1Y)
            .putFloat(key("pump", 'x'), layout.pumpX).putFloat(key("pump", 'y'), layout.pumpY)
            .putFloat(key("boiler2", 'x'), layout.boiler2X).putFloat(key("boiler2", 'y'), layout.boiler2Y)
            .apply()
    }

    fun posFor(layout: SchemeLayout, id: String): Pair<Float, Float> = when (id) {
        "pv" -> layout.pvX to layout.pvY
        "grid" -> layout.gridX to layout.gridY
        "inverter" -> layout.inverterX to layout.inverterY
        "battery" -> layout.batteryX to layout.batteryY
        "load" -> layout.loadX to layout.loadY
        "boiler1" -> layout.boiler1X to layout.boiler1Y
        "pump" -> layout.pumpX to layout.pumpY
        else -> layout.boiler2X to layout.boiler2Y
    }

    fun withPos(layout: SchemeLayout, id: String, x: Float, y: Float): SchemeLayout = when (id) {
        "pv" -> layout.copy(pvX = x, pvY = y)
        "grid" -> layout.copy(gridX = x, gridY = y)
        "inverter" -> layout.copy(inverterX = x, inverterY = y)
        "battery" -> layout.copy(batteryX = x, batteryY = y)
        "load" -> layout.copy(loadX = x, loadY = y)
        "boiler1" -> layout.copy(boiler1X = x, boiler1Y = y)
        "pump" -> layout.copy(pumpX = x, pumpY = y)
        else -> layout.copy(boiler2X = x, boiler2Y = y)
    }
}
