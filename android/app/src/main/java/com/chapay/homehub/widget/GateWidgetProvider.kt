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
import com.chapay.homehub.data.AppConfigStorage
import com.chapay.homehub.data.GarageStatus
import com.chapay.homehub.data.UnifiedStatus

class GateWidgetProvider : AppWidgetProvider() {
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TRIGGER_GATE) {
            GateWidgetActionWorker.enqueue(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_TRIGGER_GATE = "com.chapay.homehub.widget.ACTION_TRIGGER_GATE"
        private val COLOR_TEXT = Color.parseColor("#E8F3FF")
        private val COLOR_MUTED = Color.parseColor("#B7C5D8")
        private val COLOR_BUTTON_TEXT = Color.parseColor("#EAF4FF")
        private val COLOR_BUTTON_TEXT_DISABLED = Color.parseColor("#93A3B8")

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
            val component = ComponentName(context, GateWidgetProvider::class.java)
            return manager.getAppWidgetIds(component)
        }

        private fun buildRemoteViews(context: Context, status: UnifiedStatus?, pulseActive: Boolean): RemoteViews {
            val config = AppConfigStorage.load(context)
            val garageEnabled = config.garageEnabled
            val gateState = if (garageEnabled) classifyGateState(status?.garage) else GateWidgetState.DISABLED

            val views = RemoteViews(context.packageName, R.layout.widget_gate_1x1)
            val backgroundRes = if (pulseActive) {
                R.drawable.widget_status_background_glow
            } else {
                R.drawable.widget_status_background
            }
            views.setInt(R.id.widgetGateRoot, "setBackgroundResource", backgroundRes)

            views.setTextViewText(R.id.widgetGateStateDot, "*")
            views.setInt(R.id.widgetGateStateDot, "setTextColor", gateState.dotColor)
            views.setTextViewText(R.id.widgetGateStateText, context.getString(gateState.labelRes))
            views.setInt(
                R.id.widgetGateStateText,
                "setTextColor",
                if (gateState == GateWidgetState.DISABLED) COLOR_MUTED else COLOR_TEXT,
            )

            views.setTextViewText(R.id.widgetGateActionBtn, context.getString(gateState.buttonLabelRes))
            views.setInt(
                R.id.widgetGateActionBtn,
                "setTextColor",
                if (gateState == GateWidgetState.DISABLED) COLOR_BUTTON_TEXT_DISABLED else COLOR_BUTTON_TEXT,
            )
            views.setBoolean(R.id.widgetGateActionBtn, "setEnabled", gateState != GateWidgetState.DISABLED)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val launchPendingIntent = PendingIntent.getActivity(
                context,
                101,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetGateRoot, launchPendingIntent)
            views.setOnClickPendingIntent(R.id.widgetGateStateWrap, launchPendingIntent)

            val buttonPendingIntent = if (gateState == GateWidgetState.DISABLED) {
                launchPendingIntent
            } else {
                PendingIntent.getBroadcast(
                    context,
                    102,
                    Intent(context, GateWidgetProvider::class.java).apply { action = ACTION_TRIGGER_GATE },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            views.setOnClickPendingIntent(R.id.widgetGateActionBtn, buttonPendingIntent)

            return views
        }

        private fun classifyGateState(garage: GarageStatus?): GateWidgetState {
            if (garage == null) return GateWidgetState.UNKNOWN

            val closedPin = garage.gateClosedPin
            if (closedPin >= 0) {
                return if (closedPin == 0) GateWidgetState.CLOSED else GateWidgetState.OPEN
            }

            val raw = garage.gateState.trim().lowercase()
            return when {
                raw.contains("open") -> GateWidgetState.OPEN
                raw.contains("close") -> GateWidgetState.CLOSED
                raw.contains("stop") -> GateWidgetState.STOPPED
                raw.contains("move") -> GateWidgetState.MOVING
                else -> GateWidgetState.UNKNOWN
            }
        }
    }
}

private enum class GateWidgetState(
    val labelRes: Int,
    val buttonLabelRes: Int,
    val dotColor: Int,
) {
    OPEN(
        labelRes = R.string.widget_gate_state_open,
        buttonLabelRes = R.string.widget_gate_action_close,
        dotColor = Color.parseColor("#33FF99"),
    ),
    CLOSED(
        labelRes = R.string.widget_gate_state_closed,
        buttonLabelRes = R.string.widget_gate_action_open,
        dotColor = Color.parseColor("#4F7CFF"),
    ),
    MOVING(
        labelRes = R.string.widget_gate_state_moving,
        buttonLabelRes = R.string.widget_gate_action_stop,
        dotColor = Color.parseColor("#FFB347"),
    ),
    STOPPED(
        labelRes = R.string.widget_gate_state_stopped,
        buttonLabelRes = R.string.widget_gate_action_stop,
        dotColor = Color.parseColor("#FFB347"),
    ),
    UNKNOWN(
        labelRes = R.string.widget_gate_state_unknown,
        buttonLabelRes = R.string.widget_gate_action_stop,
        dotColor = Color.parseColor("#7F8FA6"),
    ),
    DISABLED(
        labelRes = R.string.widget_gate_state_disabled,
        buttonLabelRes = R.string.widget_gate_action_disabled,
        dotColor = Color.parseColor("#7F8FA6"),
    ),
}
