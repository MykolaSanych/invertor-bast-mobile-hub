package com.chapay.homehub.compose.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chapay.homehub.compose.MainActivity
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.data.AppConfigStorage
import com.chapay.homehub.compose.data.SirioCatalog
import com.chapay.homehub.compose.data.SirioEventLogStore
import com.chapay.homehub.compose.data.SirioStatus
import com.chapay.homehub.compose.data.StatusRepository
import com.chapay.homehub.compose.data.UnifiedStatus
import com.chapay.homehub.compose.widget.StatusWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private const val CHANNEL_ONGOING = "home_hub_ongoing"
private const val CHANNEL_EVENTS = "home_hub_events"
private const val CHANNEL_CRITICAL = "home_hub_critical"
private const val NOTIF_ID_FOREGROUND = 1

// Стабільні ID за типом події - новий збіг того самого типу ЗАМІНЮЄ попередній,
// а не накопичується окремою плашкою в шторці.
private const val NOTIF_ID_PV = 100
private const val NOTIF_ID_GRID_RELAY = 101
private const val NOTIF_ID_GRID_PRESENCE = 102
private const val NOTIF_ID_GRID_MODE = 103
private const val NOTIF_ID_LOAD_MODE = 104
private const val NOTIF_ID_BOILER1_MODE = 105
private const val NOTIF_ID_PUMP_MODE = 106
private const val NOTIF_ID_BOILER2_MODE = 107
private const val NOTIF_ID_GATE = 108
private const val NOTIF_ID_INVERTER_OFFLINE = 109
private const val NOTIF_ID_LOAD_OFFLINE = 110
private const val NOTIF_ID_GARAGE_OFFLINE = 111
private const val NOTIF_ID_LOAD_PROTECT = 112
private const val NOTIF_ID_BOILER1_PUMP_PROTECT = 113
private const val NOTIF_ID_BOILER2_PROTECT = 114
private const val NOTIF_ID_SIRIO_ERROR = 115
private const val NOTIF_ID_SIRIO_MOTOR = 116

private const val OFFLINE_AFTER_MS = 90_000L
private const val PV_ON_THRESHOLD_W = 100.0
private const val PV_OFF_THRESHOLD_W = 30.0

/**
 * Фоновий сервіс: опитує модулі незалежно від того, чи відкритий якийсь екран
 * (на відміну від DashboardViewModel, чиє опитування живе лише поки застосунок на
 * передньому плані), порівнює кожен новий знімок з попереднім ([EventSnapshot],
 * збережений у SharedPreferences - переживає перезапуск сервісу) і надсилає
 * сповіщення лише для тих типів подій, які увімкнені в AppConfig.notify*.
 *
 * START_STICKY - система сама перезапускає сервіс після вбивства під тиском
 * пам'яті; BootRestartReceiver - після перезавантаження пристрою.
 */
class EventPollingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private lateinit var repository: StatusRepository

    override fun onCreate() {
        super.onCreate()
        repository = StatusRepository(applicationContext)
        createChannels()
        startForeground(NOTIF_ID_FOREGROUND, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob == null) {
            loopJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val cfg = runCatching { AppConfigStorage.load(applicationContext) }.getOrNull()
            if (cfg == null || !cfg.backgroundServiceEnabled) {
                delay(15_000L)
                continue
            }
            runCatching {
                val status = repository.fetchUnified(cfg)
                EventSnapshotStore.diffAndNotify(applicationContext, cfg, status)
                if (cfg.sirioEnabled) {
                    val sirioStatus = runCatching { repository.fetchSirioStatus(cfg) }.getOrNull()
                    EventSnapshotStore.diffAndNotifySirio(applicationContext, cfg, sirioStatus)
                }
                // Оновлюємо віджети ПРЯМО ЗВІДСИ (сервіс і так опитує модулі що кілька
                // секунд) - БЕЗ WorkManager. Раніше віджет освіжався через
                // StatusWidgetRefreshWorker, якого onUpdate лаунчера ставив ~щосекунди;
                // WorkManager на кожну постановку/завершення роботи перемикав свої
                // внутрішні компоненти (setComponentEnabledSetting), через що на наш
                // пакет сипались PACKAGE_CHANGED → лаунчер у циклі перебудовував усі
                // віджети (refreshAndBindWidgetsAndShortcuts) і скидав наш до "--"
                // (блимання/прочерки). Пряме оновлення звідси цього циклу не створює.
                if (StatusWidgetProvider.hasActiveWidgets(applicationContext)) {
                    StatusWidgetProvider.updateAllWidgets(applicationContext, status, triggerPulse = false)
                }
            }
            delay((cfg.pollIntervalSec.coerceAtLeast(5)) * 1000L)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Стеження активне", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Постійне сповіщення, що фоновий сервіс працює"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "Події", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Зміни режимів, воріт, появи/зникнення генерації тощо"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CRITICAL, "Критичні події", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Перевантаження, захист АКБ, модуль не відповідає"
            },
        )
    }

    private fun buildForegroundNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("HomeHub стежить за будинком")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }
}

/** Єдина точка запуску сервісу - з MainActivity, DashboardViewModel чи BootRestartReceiver. */
object EventPollingServiceController {
    fun start(context: Context) {
        val intent = Intent(context, EventPollingService::class.java)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, EventPollingService::class.java)) }
    }
}

/**
 * Останній відомий стан кожного поля, яке ми відстежуємо, у SharedPreferences
 * (окремий файл від AppConfigStorage) - переживає перезапуск процесу/сервісу,
 * тож рестарт не спричиняє хибний "сплеск" подій порівняно з порожнім станом.
 */
private object EventSnapshotStore {
    private const val PREFS = "home_hub_event_state"

    @Synchronized
    fun diffAndNotify(context: Context, cfg: AppConfig, status: UnifiedStatus) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = p.edit()
        val nowMs = System.currentTimeMillis()

        val inv = status.inverter
        val lc = status.loadController
        val gar = status.garage

        fun notifyIf(enabled: Boolean, id: Int, channel: String, title: String, body: String) {
            if (!enabled) return
            postNotification(context, id, channel, title, body)
        }

        // --- Інвертор: генерація, мережа (наявність/реле), режим навантаження ---
        if (inv != null) {
            editor.putLong(K_INV_SEEN, nowMs)
            editor.putBoolean(K_INV_OFFLINE, false)

            val pvWasOn = p.getBoolean(K_PV_ON, inv.pvW >= PV_ON_THRESHOLD_W)
            val pvNowOn = if (pvWasOn) inv.pvW >= PV_OFF_THRESHOLD_W else inv.pvW >= PV_ON_THRESHOLD_W
            if (pvNowOn != pvWasOn) {
                notifyIf(
                    cfg.notifyPvGeneration, NOTIF_ID_PV, CHANNEL_EVENTS, "Сонце",
                    if (pvNowOn) "З'явилась генерація (${inv.pvW.toInt()} Вт)" else "Генерація зникла",
                )
            }
            editor.putBoolean(K_PV_ON, pvNowOn)

            diffBool(p, editor, K_GRID_RELAY, inv.gridRelayOn)?.let { on ->
                notifyIf(
                    cfg.notifyGridRelay, NOTIF_ID_GRID_RELAY, CHANNEL_EVENTS, "Мережа",
                    "Реле мережі ${if (on) "увімкнено" else "вимкнено"}",
                )
            }
            diffBool(p, editor, K_GRID_PRESENT, inv.gridPresent)?.let { present ->
                notifyIf(
                    cfg.notifyGridPresence, NOTIF_ID_GRID_PRESENCE, CHANNEL_EVENTS, "Мережа",
                    if (present) "Напруга мережі з'явилась" else "Напруга мережі зникла",
                )
            }
            diffString(p, editor, K_GRID_MODE, inv.mode)?.let { mode ->
                notifyIf(cfg.notifyGridMode, NOTIF_ID_GRID_MODE, CHANNEL_EVENTS, "Мережа", "Режим: $mode")
            }
            diffString(p, editor, K_LOAD_MODE, inv.loadMode)?.let { mode ->
                notifyIf(cfg.notifyLoadMode, NOTIF_ID_LOAD_MODE, CHANNEL_EVENTS, "Навантаження", "Режим: $mode")
            }
            diffBool(p, editor, K_LOAD_PROTECT, inv.loadOverloadActive || inv.loadBatteryProtectActive)?.let { active ->
                if (active) {
                    notifyIf(
                        cfg.notifyPowerOverload, NOTIF_ID_LOAD_PROTECT, CHANNEL_CRITICAL, "Захист навантаження",
                        if (inv.loadOverloadActive) "Спрацював захист від перевантаження" else "Спрацював захист від розряду АКБ",
                    )
                }
            }
        } else {
            markOfflineIfDue(p, editor, K_INV_SEEN, K_INV_OFFLINE, nowMs, cfg.notifyModuleOffline) {
                postNotification(context, NOTIF_ID_INVERTER_OFFLINE, CHANNEL_CRITICAL, "Інвертор", "Модуль не відповідає")
            }
        }

        // --- Load controller: бойлер1, насос, спільний захист ---
        if (lc != null) {
            editor.putLong(K_LC_SEEN, nowMs)
            editor.putBoolean(K_LC_OFFLINE, false)

            diffString(p, editor, K_BOILER1_MODE, lc.boiler1Mode)?.let { mode ->
                notifyIf(cfg.notifyBoiler1Mode, NOTIF_ID_BOILER1_MODE, CHANNEL_EVENTS, "Бойлер 1", "Режим: $mode")
            }
            diffString(p, editor, K_PUMP_MODE, lc.pumpMode)?.let { mode ->
                notifyIf(cfg.notifyPumpMode, NOTIF_ID_PUMP_MODE, CHANNEL_EVENTS, "Насос", "Режим: $mode")
            }
            diffBool(p, editor, K_LC_PROTECT, lc.overloadActive || lc.batteryProtectActive)?.let { active ->
                if (active) {
                    notifyIf(
                        cfg.notifyPowerOverload, NOTIF_ID_BOILER1_PUMP_PROTECT, CHANNEL_CRITICAL, "Захист бойлера 1/насоса",
                        if (lc.overloadActive) "Спрацював захист від перевантаження" else "Спрацював захист від розряду АКБ",
                    )
                }
            }
        } else {
            markOfflineIfDue(p, editor, K_LC_SEEN, K_LC_OFFLINE, nowMs, cfg.notifyModuleOffline) {
                postNotification(context, NOTIF_ID_LOAD_OFFLINE, CHANNEL_CRITICAL, "Load controller", "Модуль не відповідає")
            }
        }

        // --- Гараж: бойлер2, ворота, захист ---
        if (gar != null) {
            editor.putLong(K_GAR_SEEN, nowMs)
            editor.putBoolean(K_GAR_OFFLINE, false)

            diffString(p, editor, K_BOILER2_MODE, gar.boiler2Mode)?.let { mode ->
                notifyIf(cfg.notifyBoiler2Mode, NOTIF_ID_BOILER2_MODE, CHANNEL_EVENTS, "Бойлер 2", "Режим: $mode")
            }
            diffString(p, editor, K_GATE_STATE, gar.gateState)?.let { state ->
                notifyIf(cfg.notifyGateState, NOTIF_ID_GATE, CHANNEL_EVENTS, "Ворота", "Стан: $state")
            }
            diffBool(p, editor, K_GAR_PROTECT, gar.overloadActive || gar.batteryProtectActive)?.let { active ->
                if (active) {
                    notifyIf(
                        cfg.notifyPowerOverload, NOTIF_ID_BOILER2_PROTECT, CHANNEL_CRITICAL, "Захист бойлера 2",
                        if (gar.overloadActive) "Спрацював захист від перевантаження" else "Спрацював захист від розряду АКБ",
                    )
                }
            }
        } else {
            markOfflineIfDue(p, editor, K_GAR_SEEN, K_GAR_OFFLINE, nowMs, cfg.notifyModuleOffline) {
                postNotification(context, NOTIF_ID_GARAGE_OFFLINE, CHANNEL_CRITICAL, "Гараж", "Модуль не відповідає")
            }
        }

        editor.apply()
    }

    /**
     * Насос Sirio - окремий HTTP API (не частина Budynok-прошивок, живить
     * [SirioCatalog]-довідник). Аварія - з РОЗШИФРОВАНОЮ назвою й описом
     * (той самий текст, що в екрані Sirio), бо саме цього просив користувач -
     * "E6" саме по собі нічого не каже без контексту.
     */
    @Synchronized
    fun diffAndNotifySirio(context: Context, cfg: AppConfig, status: SirioStatus?) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = p.edit()
        val nowMs = System.currentTimeMillis()

        if (status != null) {
            editor.putLong(K_SIRIO_SEEN, nowMs)
            editor.putBoolean(K_SIRIO_OFFLINE, false)

            val hadError = p.contains(K_SIRIO_ERROR)
            val wasError = p.getBoolean(K_SIRIO_ERROR, status.errorActive)
            if (status.errorActive && (!hadError || !wasError)) {
                val name = SirioCatalog.ERROR_NAMES[status.errorNumber] ?: "невідома"
                val desc = SirioCatalog.ERROR_DESC[status.errorNumber]
                SirioEventLogStore.add(context, "Аварія E${status.errorNumber} — $name", bad = true)
                if (cfg.notifySirioError) {
                    postNotification(
                        context, NOTIF_ID_SIRIO_ERROR, CHANNEL_CRITICAL,
                        "Насос Sirio: E${status.errorNumber} — $name",
                        desc ?: "Опис - у програмному мануалі.",
                    )
                }
            } else if (!status.errorActive && hadError && wasError) {
                SirioEventLogStore.add(context, "Аварію знято")
                if (cfg.notifySirioError) {
                    postNotification(context, NOTIF_ID_SIRIO_ERROR, CHANNEL_EVENTS, "Насос Sirio", "Аварію знято")
                }
            }
            editor.putBoolean(K_SIRIO_ERROR, status.errorActive)
            editor.putInt(K_SIRIO_ERROR_CODE, status.errorNumber)

            diffBool(p, editor, K_SIRIO_MOTOR, status.motorOn)?.let { on ->
                SirioEventLogStore.add(context, if (on) "Пуск двигуна" else "Зупинка двигуна")
                if (cfg.notifySirioMotor) {
                    postNotification(
                        context, NOTIF_ID_SIRIO_MOTOR, CHANNEL_EVENTS, "Насос Sirio",
                        if (on) "Запущено" else "Зупинено",
                    )
                }
            }
        } else {
            markOfflineIfDue(p, editor, K_SIRIO_SEEN, K_SIRIO_OFFLINE, nowMs, cfg.notifySirioError) {
                SirioEventLogStore.add(context, "Модуль не відповідає", bad = true)
                postNotification(context, NOTIF_ID_SIRIO_ERROR, CHANNEL_CRITICAL, "Насос Sirio", "Модуль не відповідає")
            }
        }
        editor.apply()
    }

    private fun markOfflineIfDue(
        p: android.content.SharedPreferences,
        editor: android.content.SharedPreferences.Editor,
        seenKey: String,
        offlineKey: String,
        nowMs: Long,
        enabled: Boolean,
        onNewlyOffline: () -> Unit,
    ) {
        val lastSeen = p.getLong(seenKey, nowMs)
        val alreadyOffline = p.getBoolean(offlineKey, false)
        if (nowMs - lastSeen >= OFFLINE_AFTER_MS && !alreadyOffline) {
            editor.putBoolean(offlineKey, true)
            if (enabled) onNewlyOffline()
        }
    }

    /** Порівнює й зберігає Boolean-поле; повертає нове значення лише якщо воно ЗМІНИЛОСЬ. */
    private fun diffBool(
        p: android.content.SharedPreferences,
        editor: android.content.SharedPreferences.Editor,
        key: String,
        newValue: Boolean,
    ): Boolean? {
        val hadValue = p.contains(key)
        val old = p.getBoolean(key, newValue)
        editor.putBoolean(key, newValue)
        return if (hadValue && old != newValue) newValue else null
    }

    /** Те саме для String-поля (режими/стани). */
    private fun diffString(
        p: android.content.SharedPreferences,
        editor: android.content.SharedPreferences.Editor,
        key: String,
        newValue: String,
    ): String? {
        val hadValue = p.contains(key)
        val old = p.getString(key, newValue)
        editor.putString(key, newValue)
        return if (hadValue && old != newValue) newValue else null
    }

    private fun postNotification(context: Context, id: Int, channel: String, title: String, body: String) {
        val openApp = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(if (channel == CHANNEL_CRITICAL) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        androidx.core.app.NotificationManagerCompat.from(context).apply {
            if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { notify(id, notification) }
            }
        }
    }

    private const val K_INV_SEEN = "inv_seen_ms"
    private const val K_INV_OFFLINE = "inv_offline"
    private const val K_LC_SEEN = "lc_seen_ms"
    private const val K_LC_OFFLINE = "lc_offline"
    private const val K_GAR_SEEN = "gar_seen_ms"
    private const val K_GAR_OFFLINE = "gar_offline"
    private const val K_PV_ON = "pv_on"
    private const val K_GRID_RELAY = "grid_relay_on"
    private const val K_GRID_PRESENT = "grid_present"
    private const val K_GRID_MODE = "grid_mode"
    private const val K_LOAD_MODE = "load_mode"
    private const val K_BOILER1_MODE = "boiler1_mode"
    private const val K_PUMP_MODE = "pump_mode"
    private const val K_BOILER2_MODE = "boiler2_mode"
    private const val K_GATE_STATE = "gate_state"
    private const val K_LOAD_PROTECT = "load_protect"
    private const val K_LC_PROTECT = "lc_protect"
    private const val K_GAR_PROTECT = "gar_protect"
    private const val K_SIRIO_SEEN = "sirio_seen_ms"
    private const val K_SIRIO_OFFLINE = "sirio_offline"
    private const val K_SIRIO_ERROR = "sirio_error"
    private const val K_SIRIO_ERROR_CODE = "sirio_error_code"
    private const val K_SIRIO_MOTOR = "sirio_motor_on"
}
