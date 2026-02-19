package com.chapay.homehub.widget

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
import com.chapay.homehub.MainActivity
import com.chapay.homehub.R
import com.chapay.homehub.data.UnifiedStatus
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class StatusWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        enqueueRefresh(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isNotEmpty()) {
            appWidgetManager.updateAppWidget(appWidgetIds, buildRemoteViews(context, null))
        }
        enqueueRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, null))
        enqueueRefresh(context)
    }

    companion object {
        private const val UNIQUE_WIDGET_REFRESH_WORK = "homehub_widget_refresh"
        private const val MULTICAST_GLOW_CLEAR_DELAY_MS = 3200L

        private val COLOR_TEMP = Color.parseColor("#00D4FF")
        private val COLOR_PV = Color.parseColor("#FFB347")
        private val COLOR_GRID = Color.parseColor("#4F7CFF")
        private val COLOR_LOAD = Color.parseColor("#FF4D6D")
        private val COLOR_BAT = Color.parseColor("#33FF99")
        private val COLOR_DIVIDER_IDLE = Color.parseColor("#805C90D9")
        private val COLOR_DIVIDER_GLOW = Color.parseColor("#CC00D4FF")

        fun enqueueRefresh(context: Context, delayMs: Long = 0L) {
            val requestBuilder = OneTimeWorkRequestBuilder<StatusWidgetRefreshWorker>()
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

        fun hasActiveWidgets(context: Context): Boolean {
            return getWidgetIds(context).isNotEmpty()
        }

        fun updateAllWidgets(context: Context, status: UnifiedStatus?) {
            val widgetIds = getWidgetIds(context)
            if (widgetIds.isEmpty()) return
            AppWidgetManager.getInstance(context).updateAppWidget(
                widgetIds,
                buildRemoteViews(context, status),
            )
            if (status?.fromMulticast == true) {
                enqueueRefresh(context, delayMs = MULTICAST_GLOW_CLEAR_DELAY_MS)
            }
        }

        private fun getWidgetIds(context: Context): IntArray {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, StatusWidgetProvider::class.java)
            return manager.getAppWidgetIds(component)
        }

        private fun buildRemoteViews(context: Context, status: UnifiedStatus?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_status_4x2)
            val metrics = WidgetMetrics.from(status)
            val isGlowActive = status?.fromMulticast == true

            val backgroundRes = if (isGlowActive) {
                R.drawable.widget_status_background_glow
            } else {
                R.drawable.widget_status_background
            }
            views.setInt(R.id.widgetRoot, "setBackgroundResource", backgroundRes)
            applyNeonColors(views, isGlowActive)

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

        private fun applyNeonColors(views: RemoteViews, isGlowActive: Boolean) {
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

            val dividerColor = if (isGlowActive) COLOR_DIVIDER_GLOW else COLOR_DIVIDER_IDLE
            views.setInt(R.id.widgetColumnDivider, "setBackgroundColor", dividerColor)
        }

        private fun formatPower(value: Double?): String {
            return value?.let { "${it.roundToInt()} W" } ?: "--"
        }

        private fun formatSoc(value: Double?): String {
            return value?.let { "${it.roundToInt()} %" } ?: "--"
        }

        private fun formatTemperature(value: Double?): String {
            return value?.let { String.format(Locale.getDefault(), "%.1f\u00B0C", it) } ?: "--"
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
