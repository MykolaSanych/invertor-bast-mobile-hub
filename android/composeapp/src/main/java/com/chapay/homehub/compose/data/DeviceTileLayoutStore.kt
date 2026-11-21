package com.chapay.homehub.compose.data

import android.content.Context

/** Позиція плитки пристрою на сітці (колонка/рядок), не в пікселях - перетягування
 * завжди "прилипає" до найближчої клітинки. */
data class TileGridPos(val col: Int, val row: Int)

data class DeviceTileLayout(
    val boiler1: TileGridPos,
    val pump: TileGridPos,
    val boiler2: TileGridPos,
    val gate: TileGridPos,
)

/**
 * Позиції плиток "Пристрої" (Бойлер1/Насос/Бойлер2/Ворота) на сітці - користувач
 * може перетягувати їх пальцем, вони "прилипають" до найближчої клітинки.
 * Суто локальне, клієнтське.
 */
object DeviceTileLayoutStore {
    private const val PREFS = "home_hub_device_tile_layout"
    private const val K_B1_COL = "b1_col"
    private const val K_B1_ROW = "b1_row"
    private const val K_PUMP_COL = "pump_col"
    private const val K_PUMP_ROW = "pump_row"
    private const val K_B2_COL = "b2_col"
    private const val K_B2_ROW = "b2_row"
    private const val K_GATE_COL = "gate_col"
    private const val K_GATE_ROW = "gate_row"

    // За замовчуванням - один ряд зліва направо, як і було до появи сітки.
    val DEFAULT = DeviceTileLayout(
        boiler1 = TileGridPos(0, 0),
        pump = TileGridPos(1, 0),
        boiler2 = TileGridPos(2, 0),
        gate = TileGridPos(3, 0),
    )

    fun load(context: Context): DeviceTileLayout {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DeviceTileLayout(
            boiler1 = TileGridPos(p.getInt(K_B1_COL, DEFAULT.boiler1.col), p.getInt(K_B1_ROW, DEFAULT.boiler1.row)),
            pump = TileGridPos(p.getInt(K_PUMP_COL, DEFAULT.pump.col), p.getInt(K_PUMP_ROW, DEFAULT.pump.row)),
            boiler2 = TileGridPos(p.getInt(K_B2_COL, DEFAULT.boiler2.col), p.getInt(K_B2_ROW, DEFAULT.boiler2.row)),
            gate = TileGridPos(p.getInt(K_GATE_COL, DEFAULT.gate.col), p.getInt(K_GATE_ROW, DEFAULT.gate.row)),
        )
    }

    fun save(context: Context, layout: DeviceTileLayout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(K_B1_COL, layout.boiler1.col).putInt(K_B1_ROW, layout.boiler1.row)
            .putInt(K_PUMP_COL, layout.pump.col).putInt(K_PUMP_ROW, layout.pump.row)
            .putInt(K_B2_COL, layout.boiler2.col).putInt(K_B2_ROW, layout.boiler2.row)
            .putInt(K_GATE_COL, layout.gate.col).putInt(K_GATE_ROW, layout.gate.row)
            .apply()
    }
}
