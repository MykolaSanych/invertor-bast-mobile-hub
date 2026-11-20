package com.chapay.homehub.compose.data

import android.content.Context

/**
 * Позиції вузлів-сателітів (Сонце/Мережа/Споживання) і ядра (АКБ) на екрані
 * "Огляд" (у dp, у системі координат канви - 340x410) - користувач може
 * перетягувати їх пальцем (довге натискання + рух, щоб не плутати зі звичайним
 * дотиком-відкриттям картки). Суто локальне, клієнтське - жодна прошивка чи
 * сервер про це не знають.
 */
data class ConstellationLayout(
    val sunX: Float, val sunY: Float,
    val gridX: Float, val gridY: Float,
    val loadX: Float, val loadY: Float,
    val coreX: Float, val coreY: Float,
)

object ConstellationLayoutStore {
    private const val PREFS = "home_hub_constellation_layout"
    private const val K_SUN_X = "sun_x"
    private const val K_SUN_Y = "sun_y"
    private const val K_GRID_X = "grid_x"
    private const val K_GRID_Y = "grid_y"
    private const val K_LOAD_X = "load_x"
    private const val K_LOAD_Y = "load_y"
    private const val K_CORE_X = "core_x"
    private const val K_CORE_Y = "core_y"

    // Початкова асиметрична розкладка за задумом: Сонце - короткий лінк під
    // заголовком, Мережа - довгий лінк і нижче за решту, Споживання і ядро -
    // як і в першій версії схеми.
    val DEFAULT = ConstellationLayout(
        sunX = 62f, sunY = 34f,
        gridX = 258f, gridY = 295f,
        loadX = 50f, loadY = 239f,
        coreX = 170f, coreY = 170f,
    )

    fun load(context: Context): ConstellationLayout {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ConstellationLayout(
            sunX = p.getFloat(K_SUN_X, DEFAULT.sunX),
            sunY = p.getFloat(K_SUN_Y, DEFAULT.sunY),
            gridX = p.getFloat(K_GRID_X, DEFAULT.gridX),
            gridY = p.getFloat(K_GRID_Y, DEFAULT.gridY),
            loadX = p.getFloat(K_LOAD_X, DEFAULT.loadX),
            loadY = p.getFloat(K_LOAD_Y, DEFAULT.loadY),
            coreX = p.getFloat(K_CORE_X, DEFAULT.coreX),
            coreY = p.getFloat(K_CORE_Y, DEFAULT.coreY),
        )
    }

    fun save(context: Context, layout: ConstellationLayout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(K_SUN_X, layout.sunX)
            .putFloat(K_SUN_Y, layout.sunY)
            .putFloat(K_GRID_X, layout.gridX)
            .putFloat(K_GRID_Y, layout.gridY)
            .putFloat(K_LOAD_X, layout.loadX)
            .putFloat(K_LOAD_Y, layout.loadY)
            .putFloat(K_CORE_X, layout.coreX)
            .putFloat(K_CORE_Y, layout.coreY)
            .apply()
    }
}
