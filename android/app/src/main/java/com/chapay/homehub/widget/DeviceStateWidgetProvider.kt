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
        // Синхронізовано з порогом "насос працює" у load_controller/hub.js:
        // режим очікування контролера насоса споживає десятки ват, тому
        // "робота" вважається лише вище 150 Вт.
        private const val PUMP_POWER_ON_THRESHOLD_W = 150.0

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

            applyOnOffRow(views, R.id.widgetDevBoiler1Dot, R.id.widgetDevBoiler1State, load?.boiler1On)
            applyOnOffRow(views, R.id.widgetDevBoiler2Dot, R.id.widgetDevBoiler2State, garage?.boiler2On)
            applyOnOffRow(
                views,
                R.id.widgetDevPumpDot,
                R.id.widgetDevPumpState,
                load?.pumpPower?.takeIf { it.isFinite() }?.let { it > PUMP_POWER_ON_THRESHOLD_W },
            )
            applyOnOffRow(views, R.id.widgetDevLightDot, R.id.widgetDevLightState, garage?.garageLightOn)
            applyGateRow(context, views, garage)

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

        private fun applyOnOffRow(views: RemoteViews, dotId: Int, stateId: Int, on: Boolean?) {
            val color = when (on) {
                true -> COLOR_ON
                false -> COLOR_OFF
                null -> COLOR_UNKNOWN
            }
            val text = when (on) {
                true -> "УВІМК"
                false -> "ВИМК"
                null -> "--"
            }
            views.setInt(dotId, "setTextColor", color)
            views.setTextViewText(stateId, text)
            views.setInt(stateId, "setTextColor", color)
        }

        private fun applyGateRow(context: Context, views: RemoteViews, garage: GarageStatus?) {
            if (garage == null) {
                views.setInt(R.id.widgetDevGateDot, "setTextColor", COLOR_UNKNOWN)
                views.setTextViewText(R.id.widgetDevGateState, "--")
                views.setInt(R.id.widgetDevGateState, "setTextColor", COLOR_UNKNOWN)
                return
            }

            val closedPin = garage.gateClosedPin
            val raw = garage.gateState.trim().lowercase()
            val (textRes, color) = when {
                closedPin == 0 -> R.string.widget_gate_state_closed to COLOR_GATE_CLOSED
                closedPin > 0 -> R.string.widget_gate_state_open to COLOR_GATE_OPEN
                raw.contains("open") -> R.string.widget_gate_state_open to COLOR_GATE_OPEN
                raw.contains("close") -> R.string.widget_gate_state_closed to COLOR_GATE_CLOSED
                raw.contains("stop") || raw.contains("move") -> R.string.widget_gate_state_moving to COLOR_GATE_MOVING
                else -> R.string.widget_gate_state_unknown to COLOR_UNKNOWN
            }
            views.setInt(R.id.widgetDevGateDot, "setTextColor", color)
            views.setTextViewText(R.id.widgetDevGateState, context.getString(textRes))
            views.setInt(R.id.widgetDevGateState, "setTextColor", color)
        }
    }
}
