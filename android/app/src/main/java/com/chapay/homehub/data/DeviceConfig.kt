package com.chapay.homehub.data

import android.content.Context

data class AppConfig(
    val inverterBaseUrl: String = "http://192.168.1.2",
    val inverterPassword: String = "admin",
    val loadControllerBaseUrl: String = "http://192.168.1.3",
    val loadControllerPassword: String = "admin",
    val garageBaseUrl: String = "http://192.168.1.4",
    val garagePassword: String = "admin",
    val pollIntervalSec: Int = 5,
    val inverterEnabled: Boolean = true,
    val loadControllerEnabled: Boolean = true,
    val garageEnabled: Boolean = true,
    val realtimeMonitorEnabled: Boolean = false,
    val realtimePollIntervalSec: Int = 5,
    val notifyPvGeneration: Boolean = true,
    val notifyGridRelay: Boolean = true,
    val notifyGridPresence: Boolean = true,
    val notifyGridMode: Boolean = true,
    val notifyLoadMode: Boolean = true,
    val notifyBoiler1Mode: Boolean = true,
    val notifyPumpMode: Boolean = true,
    val notifyBoiler2Mode: Boolean = true,
    val notifyGateState: Boolean = true,
)

object AppConfigStorage {
    private const val PREFS = "home_hub_prefs"
    private const val K_INV_URL = "inv_url"
    private const val K_INV_PASS = "inv_pass"
    private const val K_LOAD_URL = "load_url"
    private const val K_LOAD_PASS = "load_pass"
    private const val K_GARAGE_URL = "garage_url"
    private const val K_GARAGE_PASS = "garage_pass"
    private const val K_POLL_SEC = "poll_sec"
    private const val K_INV_ENABLED = "inv_enabled"
    private const val K_LOAD_ENABLED = "load_enabled"
    private const val K_GARAGE_ENABLED = "garage_enabled"
    private const val K_REALTIME_ENABLED = "rt_enabled"
    private const val K_REALTIME_SEC = "rt_sec"
    private const val K_N_PV = "n_pv"
    private const val K_N_GRID_RELAY = "n_grid_relay"
    private const val K_N_GRID_PRESENCE = "n_grid_presence"
    private const val K_N_GRID_MODE = "n_grid_mode"
    private const val K_N_LOAD_MODE = "n_load_mode"
    private const val K_N_BOILER1 = "n_boiler1"
    private const val K_N_PUMP = "n_pump"
    private const val K_N_BOILER2 = "n_boiler2"
    private const val K_N_GATE = "n_gate"

    fun load(context: Context): AppConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppConfig(
            inverterBaseUrl = p.getString(K_INV_URL, "http://192.168.1.2") ?: "http://192.168.1.2",
            inverterPassword = p.getString(K_INV_PASS, "admin") ?: "admin",
            loadControllerBaseUrl = p.getString(K_LOAD_URL, "http://192.168.1.3") ?: "http://192.168.1.3",
            loadControllerPassword = p.getString(K_LOAD_PASS, "admin") ?: "admin",
            garageBaseUrl = p.getString(K_GARAGE_URL, "http://192.168.1.4") ?: "http://192.168.1.4",
            garagePassword = p.getString(K_GARAGE_PASS, "admin") ?: "admin",
            pollIntervalSec = p.getInt(K_POLL_SEC, 5).coerceIn(2, 60),
            inverterEnabled = p.getBoolean(K_INV_ENABLED, true),
            loadControllerEnabled = p.getBoolean(K_LOAD_ENABLED, true),
            garageEnabled = p.getBoolean(K_GARAGE_ENABLED, true),
            realtimeMonitorEnabled = p.getBoolean(K_REALTIME_ENABLED, false),
            realtimePollIntervalSec = p.getInt(K_REALTIME_SEC, 5).coerceIn(3, 60),
            notifyPvGeneration = p.getBoolean(K_N_PV, true),
            notifyGridRelay = p.getBoolean(K_N_GRID_RELAY, true),
            notifyGridPresence = p.getBoolean(K_N_GRID_PRESENCE, true),
            notifyGridMode = p.getBoolean(K_N_GRID_MODE, true),
            notifyLoadMode = p.getBoolean(K_N_LOAD_MODE, true),
            notifyBoiler1Mode = p.getBoolean(K_N_BOILER1, true),
            notifyPumpMode = p.getBoolean(K_N_PUMP, true),
            notifyBoiler2Mode = p.getBoolean(K_N_BOILER2, true),
            notifyGateState = p.getBoolean(K_N_GATE, true),
        )
    }

    fun save(context: Context, cfg: AppConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(K_INV_URL, cfg.inverterBaseUrl.trim())
            .putString(K_INV_PASS, cfg.inverterPassword)
            .putString(K_LOAD_URL, cfg.loadControllerBaseUrl.trim())
            .putString(K_LOAD_PASS, cfg.loadControllerPassword)
            .putString(K_GARAGE_URL, cfg.garageBaseUrl.trim())
            .putString(K_GARAGE_PASS, cfg.garagePassword)
            .putInt(K_POLL_SEC, cfg.pollIntervalSec.coerceIn(2, 60))
            .putBoolean(K_INV_ENABLED, cfg.inverterEnabled)
            .putBoolean(K_LOAD_ENABLED, cfg.loadControllerEnabled)
            .putBoolean(K_GARAGE_ENABLED, cfg.garageEnabled)
            .putBoolean(K_REALTIME_ENABLED, cfg.realtimeMonitorEnabled)
            .putInt(K_REALTIME_SEC, cfg.realtimePollIntervalSec.coerceIn(3, 60))
            .putBoolean(K_N_PV, cfg.notifyPvGeneration)
            .putBoolean(K_N_GRID_RELAY, cfg.notifyGridRelay)
            .putBoolean(K_N_GRID_PRESENCE, cfg.notifyGridPresence)
            .putBoolean(K_N_GRID_MODE, cfg.notifyGridMode)
            .putBoolean(K_N_LOAD_MODE, cfg.notifyLoadMode)
            .putBoolean(K_N_BOILER1, cfg.notifyBoiler1Mode)
            .putBoolean(K_N_PUMP, cfg.notifyPumpMode)
            .putBoolean(K_N_BOILER2, cfg.notifyBoiler2Mode)
            .putBoolean(K_N_GATE, cfg.notifyGateState)
            .apply()
    }
}
