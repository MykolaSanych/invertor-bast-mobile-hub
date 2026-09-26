package com.chapay.homehub.compose.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.chapay.homehub.compose.MainActivity
import com.chapay.homehub.compose.R
import com.chapay.homehub.compose.data.GarageStatus
import com.chapay.homehub.compose.data.UnifiedStatus

class DeviceStateWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        StatusWidgetProvider.enqueueRefresh(context)
    }

    // onUpdate() виявився на цьому пристрої несподівано "балакучим" -
    // AppWidgetServiceImpl/PackageManager перевикликали його раз на ~9-10с
    // (причина поза цим кодом - схоже на поведінку конкретного лаунчера/OEM,
    // а не на щось, спричинене нашими запитами). Раніше тут одразу
    // домальовувався ПОРОЖНІЙ RemoteViews (усі поля null) перед асинхронним
    // enqueueRefresh - тобто віджет щоразу на мить "блимав" рисками, а тоді
    // знову заповнювався. Тепер onUpdate лише плаче фонове оновлення й НЕ
    // чіпає вже намальований віджет - останні реальні дані лишаються на
    // екрані, поки не прийде свіжий успішний фетч. Сам StatusWidgetRefreshWorker
    // додатково не частіше ніж раз на 20с реально ходить у мережу й пульсує
    // рамку лише на РЕАЛЬНУ зміну даних (див. StatusWidgetProvider/Worker) -
    // разом ці два фікси й прибирають "постійне блимання".
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        StatusWidgetProvider.enqueueRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        StatusWidgetProvider.enqueueRefresh(context)
    }

    companion object {
        // Синхронізовано з порогами "працює" у load_controller/hub.js: бойлер
        // вважається таким, що реально гріє, вище 50 Вт, насос - вище 150 Вт
        // (у режимі очікування його контролер сам споживає десятки ват).
        private const val BOILER_POWER_WORKING_THRESHOLD_W = 50.0
        private const val PUMP_POWER_WORKING_THRESHOLD_W = 150.0

        // Якщо знімок статусу старіший за цей поріг - не показуємо кольорову
        // підсвітку "працює"/"відчинено", бо на момент показу це вже могло
        // бути неправдою; "УВІМК"/"ВИМК" лишається (це стан реле, а не
        // миттєве споживання, тож застаріває не так критично).
        private const val FRESH_STATUS_MAX_AGE_MS = 10L * 60L * 1000L

        // Приглушена, "преміальна" палітра - та сама, що й у самому застосунку
        // (ui/theme/Color.kt): AccentBoiler/AccentPump тощо, а не неонові
        // кольори старої версії віджета.
        private val COLOR_OFF = Color.parseColor("#5C6B85") // TextMuted, притлумлений
        private val COLOR_IDLE_ON = Color.parseColor("#B7C5D8") // реле увімкнено, але не працює
        private val COLOR_BOILER_WORKING = Color.parseColor("#D97757") // AccentBoiler
        private val COLOR_PUMP_WORKING = Color.parseColor("#3FB6B0") // AccentPump
        private val COLOR_LIGHT_ON = Color.parseColor("#E8C468") // теплий, приглушений золотий
        private val COLOR_GATE_OPEN = Color.parseColor("#4CBB8A") // AccentBattery (зелений)
        private val COLOR_GATE_CLOSED = Color.parseColor("#5B8DEF") // AccentGrid (синій)
        private val COLOR_GATE_MOVING = Color.parseColor("#E8A94C") // AccentPv (бурштиновий)
        private val COLOR_UNKNOWN = COLOR_OFF

        fun hasActiveWidgets(context: Context): Boolean {
            return getWidgetIds(context).isNotEmpty()
        }

        fun updateAllWidgets(context: Context, status: UnifiedStatus?, pulseActive: Boolean) {
            val widgetIds = getWidgetIds(context)
            if (widgetIds.isEmpty()) return
            AppWidgetManager.getInstance(context).updateAppWidget(
                widgetIds,
                buildRemoteViews(context, status, pulseActive),
            )
        }

        private fun getWidgetIds(context: Context): IntArray {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, DeviceStateWidgetProvider::class.java)
            return manager.getAppWidgetIds(component)
        }

        private fun buildRemoteViews(context: Context, status: UnifiedStatus?, pulseActive: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_devices)

            val backgroundRes = if (pulseActive) {
                R.drawable.widget_devices_background_glow
            } else {
                R.drawable.widget_devices_background
            }
            views.setInt(R.id.widgetDevicesRoot, "setBackgroundResource", backgroundRes)

            val load = status?.loadController
            val garage = status?.garage
            val isFresh = status != null &&
                (System.currentTimeMillis() - status.updatedAtMs) < FRESH_STATUS_MAX_AGE_MS

            applyPowerDeviceRow(
                views,
                dotId = R.id.widgetDevBoiler1Dot,
                stateId = R.id.widgetDevBoiler1State,
                on = load?.boiler1On,
                powerW = load?.boilerPower,
                thresholdW = BOILER_POWER_WORKING_THRESHOLD_W,
                isFresh = isFresh,
                workingColor = COLOR_BOILER_WORKING,
            )
            applyPowerDeviceRow(
                views,
                dotId = R.id.widgetDevBoiler2Dot,
                stateId = R.id.widgetDevBoiler2State,
                on = garage?.boiler2On,
                powerW = garage?.boilerPower,
                thresholdW = BOILER_POWER_WORKING_THRESHOLD_W,
                isFresh = isFresh,
                workingColor = COLOR_BOILER_WORKING,
            )
            applyPowerDeviceRow(
                views,
                dotId = R.id.widgetDevPumpDot,
                stateId = R.id.widgetDevPumpState,
                on = load?.pumpOn,
                powerW = load?.pumpPower,
                thresholdW = PUMP_POWER_WORKING_THRESHOLD_W,
                isFresh = isFresh,
                workingColor = COLOR_PUMP_WORKING,
            )
            applySimpleOnOffRow(
                views,
                dotId = R.id.widgetDevLightDot,
                stateId = R.id.widgetDevLightState,
                on = garage?.garageLightOn,
                onColor = COLOR_LIGHT_ON,
            )
            applyGateRow(views, garage)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val launchPendingIntent = PendingIntent.getActivity(
                context,
                103,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetDevicesRoot, launchPendingIntent)

            return views
        }

        // Кольорова крапка + текст разом несуть увесь стан (раніше стан ще й
        // підсвічував увесь рядок великим напівпрозорим прямокутником - тепер
        // цього немає: тонше й спокійніше, менше "блимає" оком навіть коли
        // дані реально змінюються щохвилини).
        private fun applyDot(views: RemoteViews, dotId: Int, color: Int) {
            views.setInt(dotId, "setColorFilter", color)
        }

        // Реле "увімкнено" (режим дозволяє роботу) - це не те саме, що
        // пристрій зараз реально споживає струм (бойлер догрів воду й чекає,
        // насос стоїть). "УВІМК"/"ВИМК" завжди показує стан реле; лише коли
        // потужність перевищує поріг - і крапка, і текст переходять на
        // яскравий колір пристрою (сигнал "зараз реально працює").
        private fun applyPowerDeviceRow(
            views: RemoteViews,
            dotId: Int,
            stateId: Int,
            on: Boolean?,
            powerW: Double?,
            thresholdW: Double,
            isFresh: Boolean,
            workingColor: Int,
        ) {
            views.setTextViewText(stateId, onOffText(on))
            val isWorking = isFresh && on == true && powerW != null && powerW.isFinite() && powerW > thresholdW
            val color = when {
                isWorking -> workingColor
                on == true -> COLOR_IDLE_ON
                on == false -> COLOR_OFF
                else -> COLOR_UNKNOWN
            }
            views.setInt(stateId, "setTextColor", color)
            applyDot(views, dotId, color)
        }

        // Світло - просте реле без поняття "працює", тому в нього лише два
        // стани: увімкнено (теплий золотий) або вимкнено (приглушений сірий).
        private fun applySimpleOnOffRow(views: RemoteViews, dotId: Int, stateId: Int, on: Boolean?, onColor: Int) {
            views.setTextViewText(stateId, onOffText(on))
            val color = when (on) {
                true -> onColor
                false -> COLOR_OFF
                null -> COLOR_UNKNOWN
            }
            views.setInt(stateId, "setTextColor", color)
            applyDot(views, dotId, color)
        }

        private fun onOffText(on: Boolean?): String = when (on) {
            true -> "УВІМК"
            false -> "ВИМК"
            null -> "--"
        }

        private fun applyGateRow(views: RemoteViews, garage: GarageStatus?) {
            if (garage == null) {
                views.setTextViewText(R.id.widgetDevGateState, "--")
                views.setInt(R.id.widgetDevGateState, "setTextColor", COLOR_UNKNOWN)
                applyDot(views, R.id.widgetDevGateDot, COLOR_UNKNOWN)
                return
            }

            val closedPin = garage.gateClosedPin
            val raw = garage.gateState.trim().lowercase()
            val (text, color) = when {
                closedPin == 0 -> "зач." to COLOR_GATE_CLOSED
                closedPin > 0 -> "відч." to COLOR_GATE_OPEN
                raw.contains("open") -> "відч." to COLOR_GATE_OPEN
                raw.contains("close") -> "зач." to COLOR_GATE_CLOSED
                raw.contains("stop") || raw.contains("move") -> "рух" to COLOR_GATE_MOVING
                else -> "--" to COLOR_UNKNOWN
            }
            views.setTextViewText(R.id.widgetDevGateState, text)
            views.setInt(R.id.widgetDevGateState, "setTextColor", color)
            applyDot(views, R.id.widgetDevGateDot, color)
        }
    }
}
