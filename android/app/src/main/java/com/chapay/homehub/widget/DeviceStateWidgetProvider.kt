package com.chapay.homehub.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.chapay.homehub.MainActivity
import com.chapay.homehub.R
import com.chapay.homehub.data.GarageStatus
import com.chapay.homehub.data.UnifiedStatus

class DeviceStateWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        StatusWidgetProvider.enqueueRefresh(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isNotEmpty()) {
            appWidgetManager.updateAppWidget(appWidgetIds, buildRemoteViews(context, null, pulseActive = false))
        }
        StatusWidgetProvider.enqueueRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, null, pulseActive = false))
        StatusWidgetProvider.enqueueRefresh(context)
    }

    companion object {
        // Синхронізовано з порогами "працює" у load_controller/hub.js: бойлер
        // вважається таким, що реально гріє, вище 50 Вт, насос - вище 150 Вт
        // (у режимі очікування його контролер сам споживає десятки ват).
        private const val BOILER_POWER_WORKING_THRESHOLD_W = 50.0
        private const val PUMP_POWER_WORKING_THRESHOLD_W = 150.0

        private val COLOR_ON = Color.parseColor("#33FF99")
        private val COLOR_OFF = Color.parseColor("#7F8FA6")
        private val COLOR_GATE_OPEN = Color.parseColor("#33FF99")
        private val COLOR_GATE_CLOSED = Color.parseColor("#4F7CFF")
        private val COLOR_GATE_MOVING = Color.parseColor("#FFB347")
        private val COLOR_UNKNOWN = Color.parseColor("#7F8FA6")

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

            applyPowerDeviceRow(
                views,
                rowId = R.id.widgetDevBoiler1Row,
                stateId = R.id.widgetDevBoiler1State,
                on = load?.boiler1On,
                powerW = load?.boilerPower,
                thresholdW = BOILER_POWER_WORKING_THRESHOLD_W,
            )
            applyPowerDeviceRow(
                views,
                rowId = R.id.widgetDevBoiler2Row,
                stateId = R.id.widgetDevBoiler2State,
                on = garage?.boiler2On,
                powerW = garage?.boilerPower,
                thresholdW = BOILER_POWER_WORKING_THRESHOLD_W,
            )
            applyPowerDeviceRow(
                views,
                rowId = R.id.widgetDevPumpRow,
                stateId = R.id.widgetDevPumpState,
                on = load?.pumpOn,
                powerW = load?.pumpPower,
                thresholdW = PUMP_POWER_WORKING_THRESHOLD_W,
            )
            applySimpleOnOffRow(views, R.id.widgetDevLightState, garage?.garageLightOn)
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

        private fun onOffColor(on: Boolean?): Int = when (on) {
            true -> COLOR_ON
            false -> COLOR_OFF
            null -> COLOR_UNKNOWN
        }

        private fun onOffText(on: Boolean?): String = when (on) {
            true -> "УВІМК"
            false -> "ВИМК"
            null -> "--"
        }

        private fun applySimpleOnOffRow(views: RemoteViews, stateId: Int, on: Boolean?) {
            views.setTextViewText(stateId, onOffText(on))
            views.setInt(stateId, "setTextColor", onOffColor(on))
        }

        // Реле "увімкнено" (режим дозволяє роботу) - це не те саме, що
        // пристрій зараз реально споживає струм (бойлер догрів воду й чекає,
        // насос стоїть). "УВІМК"/"ВИМК" завжди показує стан реле; коли
        // потужність перевищує поріг - поле рядка додатково підсвічується
        // червоним, як явний індикатор "зараз працює".
        private fun applyPowerDeviceRow(
            views: RemoteViews,
            rowId: Int,
            stateId: Int,
            on: Boolean?,
            powerW: Double?,
            thresholdW: Double,
        ) {
            views.setTextViewText(stateId, onOffText(on))
            views.setInt(stateId, "setTextColor", onOffColor(on))

            val isWorking = on == true && powerW != null && powerW.isFinite() && powerW > thresholdW
            if (isWorking) {
                views.setInt(rowId, "setBackgroundResource", R.drawable.widget_device_row_working)
            }
        }

        private fun applyGateRow(views: RemoteViews, garage: GarageStatus?) {
            if (garage == null) {
                views.setTextViewText(R.id.widgetDevGateState, "--")
                views.setInt(R.id.widgetDevGateState, "setTextColor", COLOR_UNKNOWN)
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
        }
    }
}
