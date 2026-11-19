package com.chapay.homehub.compose.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.chapay.homehub.compose.MainActivity
import com.chapay.homehub.compose.R
import com.chapay.homehub.compose.data.UnifiedStatus
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class StatusWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        enqueueRefresh(context)
    }

    // Лише СИНХРОННО повертаємо останні дані з кешу. WorkManager-роботу тут більше
    // НЕ ставимо: її часта постановка (onUpdate лаунчера прилітав ~щосекунди)
    // смикала внутрішні компоненти WorkManager → PACKAGE_CHANGED на наш пакет →
    // цикл перебудови віджетів у лаунчері, що й скидав віджет у "--". Свіжі дані
    // кладе EventPollingService (що кілька секунд) напряму, без WorkManager.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        repaintFromCache(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        repaintFromCache(context)
    }

    companion object {
        private const val UNIQUE_WIDGET_REFRESH_WORK = "homehub_compose_widget_refresh"
        private const val INPUT_SUPPRESS_PULSE = "suppress_widget_pulse"
        private const val WIDGET_PULSE_CLEAR_DELAY_MS = 3200L

        private val COLOR_TEMP = Color.parseColor("#00D4FF")
        private val COLOR_PV = Color.parseColor("#FFB347")
        private val COLOR_GRID = Color.parseColor("#4F7CFF")
        private val COLOR_LOAD = Color.parseColor("#FF4D6D")
        private val COLOR_BAT = Color.parseColor("#33FF99")
        private val COLOR_DIVIDER_IDLE = Color.parseColor("#805C90D9")
        private val COLOR_DIVIDER_GLOW = Color.parseColor("#CC00D4FF")

        fun enqueueRefresh(context: Context, delayMs: Long = 0L, suppressPulse: Boolean = false) {
            val requestBuilder = OneTimeWorkRequestBuilder<StatusWidgetRefreshWorker>()
                .setInputData(workDataOf(INPUT_SUPPRESS_PULSE to suppressPulse))
            if (delayMs > 0L) {
                requestBuilder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            }
            val request = requestBuilder.build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WIDGET_REFRESH_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        internal fun shouldSuppressPulse(inputData: androidx.work.Data): Boolean {
            return inputData.getBoolean(INPUT_SUPPRESS_PULSE, false)
        }

        fun hasActiveWidgets(context: Context): Boolean {
            return getWidgetIds(context).isNotEmpty() ||
                GateWidgetProvider.hasActiveWidgets(context) ||
                DeviceStateWidgetProvider.hasActiveWidgets(context)
        }

        // Останній ДІЙСНИЙ знімок кожного модуля, показаний фоновим воркером - лише для
        // нього (StatusWidgetRefreshWorker створює НОВИЙ StatusRepository на кожен
        // запуск, тобто без цієї пам'яті кожен запуск "забуває", що показував минулого
        // разу). Це справжня причина, чому окремі рядки віджета (бойлер/насос/світло/
        // ворота) на мить показували реальний стан, а тоді знову падали на "--": фоновий
        // фетч опитує три модулі незалежно, і коли рівно ОДИН з них не встиг відповісти
        // (а на цьому пристрої фонові запити регулярно підрізає OEM-керування батареєю/
        // мережею - див. коментар у Worker), його поле status.* просто ставало null, і
        // рядки, що залежать саме від нього, миттю "порожніли" - хоча реальних даних
        // цей модуль хвилини тому давав достатньо. mergeWithLastGoodAutoStatus "долатує"
        // відсутній модуль останнім дійсним знімком САМЕ ЦЬОГО модуля, а не всього
        // статусу цілком - точно як робить живий застосунок (DashboardViewModel) зі
        // своїм окремим "липким" станом.
        // Живий застосунок опитує на своєму корутин-диспетчері, а WorkManager
        // виконує Worker на власному фоновому потоці - тобто mergeWithLastGoodAutoStatus
        // могли викликати ДВА РІЗНІ ПОТОКИ одночасно. lastGoodAutoStatus був лише
        // @Volatile (гарантує видимість запису, але не атомарність "прочитати-
        // змінити-записати"): якщо обидва потоки встигали прочитати старе значення
        // ДО того, як інший запише нове, останній запис "перемагав" і міг відкотити
        // щойно показані дані на менш повні - подив("стан з'являється на долю секунди
        // і зникає"). Тепер злиття кешу И сам виклик updateAppWidget серіалізовані
        // одним lock - другий потік завжди бачить результат першого, тож ніколи не
        // намалює менш повні дані, ніж уже на екрані.
        private val widgetUpdateLock = Any()

        @Volatile
        private var lastGoodAutoStatus: UnifiedStatus? = null

        // lastGoodAutoStatus живе лише в пам'яті процесу - після того, як систему
        // вбиває процес застосунку, він порожній, і холодний фетч, що не достукався
        // до модуля (ZeroTier у Doze), давав "--". Один раз на процес піднімаємо
        // останній дійсний знімок із диска (WidgetStatusCache), щоб холодний старт
        // одразу мав чим залатати відсутні модулі. Викликати ДО першого
        // mergeWithLastGoodAutoStatus (на початку updateAllWidgets і воркера).
        @Volatile
        private var cachePrimed = false

        internal fun primeFromDiskIfNeeded(context: Context) {
            if (cachePrimed) return
            synchronized(widgetUpdateLock) {
                if (cachePrimed) return
                if (lastGoodAutoStatus == null) {
                    WidgetStatusCache.load(context)?.let { lastGoodAutoStatus = it }
                }
                cachePrimed = true
            }
        }

        internal fun mergeWithLastGoodAutoStatus(fresh: UnifiedStatus): UnifiedStatus {
            synchronized(widgetUpdateLock) {
                val last = lastGoodAutoStatus
                val merged = UnifiedStatus(
                    inverter = fresh.inverter ?: last?.inverter,
                    loadController = fresh.loadController ?: last?.loadController,
                    garage = fresh.garage ?: last?.garage,
                    updatedAtMs = fresh.updatedAtMs,
                )
                lastGoodAutoStatus = merged
                return merged
            }
        }

        // Останній "відбиток" показаних значень - лише для авто-фонових оновлень
        // (StatusWidgetRefreshWorker), щоб пульс-підсвітка спрацьовувала на РЕАЛЬНУ
        // зміну даних, а не на кожен успішний фоновий запит (OS/лаунчер на цьому
        // пристрої кличе onUpdate раз на ~9-10с - раніше кожен такий тик підсвічував
        // рамку, тобто віджет "постійно блимав", хоча дані щоразу були майже ті самі).
        // Ручні дії (кнопка воріт) і живе опитування з відкритого застосунку пульс не
        // питають тут - вони й так свідомо викликають triggerPulse=true/false самі.
        @Volatile
        private var lastAutoSignature: String? = null

        /** true, якщо [status] помітно відрізняється від того, що востаннє показав фоновий воркер. */
        internal fun hasChangedSinceLastAutoRefresh(status: UnifiedStatus?): Boolean {
            val signature = buildSignature(status)
            val changed = signature != lastAutoSignature
            lastAutoSignature = signature
            return changed
        }

        // Округлення до 3 Вт / 1% SOC - невеликий шум датчиків (±1-2 Вт) не має
        // сам собою підпалювати підсвітку "щойно оновилось".
        private fun buildSignature(status: UnifiedStatus?): String {
            val inverter = status?.inverter
            val load = status?.loadController
            val garage = status?.garage
            fun p(v: Double?) = v?.let { (it / 3.0).roundToInt() } ?: Int.MIN_VALUE
            fun s(v: Double?) = v?.roundToInt() ?: Int.MIN_VALUE
            return listOf(
                s(pickNumber(inverter?.bmeExtTemp, inverter?.bmeTemp, load?.bmeTemp, garage?.bmeTemp)?.let { it * 10 }),
                p(pickNumber(inverter?.pvW, load?.pvW, garage?.pvW)),
                p(pickNumber(inverter?.gridW, load?.gridW, garage?.gridW)),
                p(pickNumber(inverter?.loadW, load?.loadW, garage?.loadW)),
                p(pickNumber(inverter?.batteryPower, load?.batteryPower, garage?.batteryPower)),
                s(pickNumber(inverter?.batterySoc, load?.batterySoc, garage?.batterySoc)),
                load?.boiler1On, p(load?.boilerPower),
                garage?.boiler2On, p(garage?.boilerPower),
                load?.pumpOn, p(load?.pumpPower),
                garage?.garageLightOn,
                garage?.gateClosedPin, garage?.gateState,
            ).joinToString("|")
        }

        // ВАЖЛИВО: живий застосунок (DashboardViewModel, поки відкритий) і фоновий
        // StatusWidgetRefreshWorker оновлюють ті самі три віджети НЕЗАЛЕЖНО, і
        // раніше кожен тримав власний "липкий" кеш (застосунок - у DashboardUiState,
        // воркер - lastGoodAutoStatus нижче). Коли обидва шляхи спрацьовували по черзі,
        // воркер міг переписати щойно показані живим застосунком СВІЖІ дані своїм
        // ЗАСТАРІЛИМ кешем (бо той жодного разу не бачив свіжих даних застосунку) -
        // саме це й давало "цифри на мить стають іншими". Виклик
        // mergeWithLastGoodAutoStatus() ТУТ, в одній спільній точці для ОБОХ шляхів,
        // гарантує, що обидва завжди пишуть/читають той самий кеш - жоден не може
        // відкотити те, що щойно показав інший.
        fun updateAllWidgets(context: Context, status: UnifiedStatus?, triggerPulse: Boolean = true) {
            // Усе злиття+малювання - в ОДНОМУ synchronized-блоці (не лише сам мердж):
            // інакше два потоки могли б обидва змерджити (кожен побачивши результат
            // іншого - ОК), але потім намалювати віджет у "неправильному" порядку -
            // другий виклик updateAppWidget завжди мусить бути щонайменше таким же
            // повним, як перший, а не випадково раніше змердженим значенням.
            synchronized(widgetUpdateLock) {
                // Перед першим злиттям цього процесу підтягуємо останній знімок із
                // диска - інакше холодний старт не має чим залатати відсутні модулі.
                primeFromDiskIfNeeded(context)
                // mergeWithLastGoodAutoStatus сам бере widgetUpdateLock - Kotlin/JVM
                // synchronized є reentrant (той самий потік може взяти лок повторно),
                // тож вкладений виклик тут безпечний, не дедлок.
                val merged = mergeWithLastGoodAutoStatus(status ?: UnifiedStatus())

                // Повні прочерки не малюємо НІКОЛИ - краще лишити на екрані останній
                // кадр, ніж блимнути порожнечею.
                if (!hasAnyModuleData(merged)) return

                // Зберігаємо непорожній знімок, щоб наступний холодний старт показав
                // останні реальні дані, а не прочерки.
                WidgetStatusCache.save(context, merged)

                renderAll(context, merged, pulseActive = triggerPulse)

                if (triggerPulse) {
                    enqueueRefresh(context, delayMs = WIDGET_PULSE_CLEAR_DELAY_MS, suppressPulse = true)
                }
            }
        }

        // Малює ВСІ три віджети з останнього відомого (кешованого) стану СИНХРОННО,
        // без жодної мережі. Головний засіб проти мерехтіння: лаунчер скидає
        // RemoteViews до "--" і кличе onUpdate мало не щосекунди, а свіжі дані
        // приходять асинхронним воркером реально лише раз на 20с (throttle), тож між
        // скиданнями видно прочерки. Перемальовуючи кеш на КОЖЕН onUpdate, тримаємо
        // дані на екрані постійно.
        fun repaintFromCache(context: Context) {
            synchronized(widgetUpdateLock) {
                primeFromDiskIfNeeded(context)
                val cached = lastGoodAutoStatus
                if (cached == null || !hasAnyModuleData(cached)) return
                renderAll(context, cached, pulseActive = false)
            }
        }

        // Лаунчер Motorola сам (не наш код) у циклі ~1с перебудовує віджети
        // (refreshAndBindWidgetsAndShortcuts), скидаючи наш до initialLayout "--". Тому
        // дедуплікацію прибрано: мусимо перемальовувати дані на КОЖЕН onUpdate, інакше
        // після чергового скидання лаунчера віджет лишається на "--" (саме так і сталось,
        // коли рендер пропускали за "незмінним" станом). Залишкове блимання - наслідок
        // цього циклу лаунчера, усунути його повністю з боку застосунку не можна.
        private fun renderAll(context: Context, merged: UnifiedStatus, pulseActive: Boolean) {
            val widgetIds = getWidgetIds(context)
            if (widgetIds.isNotEmpty()) {
                AppWidgetManager.getInstance(context).updateAppWidget(
                    widgetIds,
                    buildRemoteViews(context, merged, pulseActive = pulseActive),
                )
            }

            GateWidgetProvider.updateAllWidgets(context, merged, pulseActive)
            DeviceStateWidgetProvider.updateAllWidgets(context, merged, pulseActive)
        }

        private fun hasAnyModuleData(status: UnifiedStatus?): Boolean {
            return status?.inverter != null || status?.loadController != null || status?.garage != null
        }

        private fun getWidgetIds(context: Context): IntArray {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, StatusWidgetProvider::class.java)
            return manager.getAppWidgetIds(component)
        }

        private fun buildRemoteViews(context: Context, status: UnifiedStatus?, pulseActive: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_status_4x2)
            val metrics = WidgetMetrics.from(status)

            val backgroundRes = if (pulseActive) {
                R.drawable.widget_status_background_glow
            } else {
                R.drawable.widget_status_background
            }
            views.setInt(R.id.widgetRoot, "setBackgroundResource", backgroundRes)
            applyNeonColors(views, pulseActive)

            views.setTextViewText(R.id.widgetValueOutsideTemp, formatTemperature(metrics.outsideTempC))
            views.setTextViewText(R.id.widgetValuePvPower, formatPower(metrics.pvPowerW))
            views.setTextViewText(R.id.widgetValueGridPower, formatPower(metrics.gridPowerW))
            views.setTextViewText(R.id.widgetValueLoadPower, formatPower(metrics.loadPowerW))
            views.setTextViewText(R.id.widgetValueBatteryPower, formatPower(metrics.batteryPowerW))
            views.setTextViewText(R.id.widgetValueBatterySoc, formatSoc(metrics.batterySoc))

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val launchPendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, launchPendingIntent)

            return views
        }

        private fun applyNeonColors(views: RemoteViews, pulseActive: Boolean) {
            views.setInt(R.id.widgetLabelOutsideTemp, "setTextColor", COLOR_TEMP)
            views.setInt(R.id.widgetValueOutsideTemp, "setTextColor", COLOR_TEMP)

            views.setInt(R.id.widgetLabelPvPower, "setTextColor", COLOR_PV)
            views.setInt(R.id.widgetValuePvPower, "setTextColor", COLOR_PV)

            views.setInt(R.id.widgetLabelGridPower, "setTextColor", COLOR_GRID)
            views.setInt(R.id.widgetValueGridPower, "setTextColor", COLOR_GRID)

            views.setInt(R.id.widgetLabelLoadPower, "setTextColor", COLOR_LOAD)
            views.setInt(R.id.widgetValueLoadPower, "setTextColor", COLOR_LOAD)

            views.setInt(R.id.widgetLabelBatteryPower, "setTextColor", COLOR_BAT)
            views.setInt(R.id.widgetValueBatteryPower, "setTextColor", COLOR_BAT)
            views.setInt(R.id.widgetLabelBatterySoc, "setTextColor", COLOR_BAT)
            views.setInt(R.id.widgetValueBatterySoc, "setTextColor", COLOR_BAT)

            val dividerColor = if (pulseActive) COLOR_DIVIDER_GLOW else COLOR_DIVIDER_IDLE
            views.setInt(R.id.widgetColumnDivider, "setBackgroundColor", dividerColor)
        }

        private fun formatPower(value: Double?): String {
            return value?.let { "${it.roundToInt()} W" } ?: "--"
        }

        private fun formatSoc(value: Double?): String {
            return value?.let { "${it.roundToInt()} %" } ?: "--"
        }

        private fun formatTemperature(value: Double?): String {
            return value?.let { String.format(Locale.getDefault(), "%.1f°C", it) } ?: "--"
        }
    }
}

private data class WidgetMetrics(
    val outsideTempC: Double?,
    val pvPowerW: Double?,
    val gridPowerW: Double?,
    val loadPowerW: Double?,
    val batteryPowerW: Double?,
    val batterySoc: Double?,
) {
    companion object {
        fun from(status: UnifiedStatus?): WidgetMetrics {
            val inverter = status?.inverter
            val load = status?.loadController
            val garage = status?.garage
            return WidgetMetrics(
                outsideTempC = pickNumber(
                    inverter?.bmeExtTemp,
                    inverter?.bmeTemp,
                    load?.bmeTemp,
                    garage?.bmeTemp,
                ),
                pvPowerW = pickNumber(inverter?.pvW, load?.pvW, garage?.pvW),
                gridPowerW = pickNumber(inverter?.gridW, load?.gridW, garage?.gridW),
                loadPowerW = pickNumber(inverter?.loadW, load?.loadW, garage?.loadW),
                batteryPowerW = pickNumber(
                    inverter?.batteryPower,
                    load?.batteryPower,
                    garage?.batteryPower,
                ),
                batterySoc = pickNumber(
                    inverter?.batterySoc,
                    load?.batterySoc,
                    garage?.batterySoc,
                ),
            )
        }
    }
}

private fun pickNumber(vararg values: Double?): Double? {
    return values.firstOrNull { it != null && it.isFinite() }
}
