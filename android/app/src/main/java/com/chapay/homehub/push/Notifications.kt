package com.chapay.homehub.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chapay.homehub.MainActivity
import com.chapay.homehub.R

const val HOME_EVENTS_CHANNEL_ID = "home_events"
const val HOME_MONITOR_CHANNEL_ID = "home_monitor"
const val MONITOR_FOREGROUND_ID = 2001

fun ensureNotificationChannel(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            HOME_EVENTS_CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
        }
        manager.createNotificationChannel(channel)

        val monitorChannel = NotificationChannel(
            HOME_MONITOR_CHANNEL_ID,
            context.getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.monitor_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(monitorChannel)
    }
}

fun showPushNotification(context: Context, title: String, body: String) {
    ensureNotificationChannel(context)
    EventJournalStore.append(context, title, body)
    val launchIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        launchIntent,
        pendingIntentFlags,
    )

    val notification = NotificationCompat.Builder(context, HOME_EVENTS_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()

    NotificationManagerCompat.from(context).notify(
        (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
        notification,
    )
}

fun buildMonitorNotification(context: Context, text: String): android.app.Notification {
    ensureNotificationChannel(context)
    val launchIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        launchIntent,
        pendingIntentFlags,
    )

    return NotificationCompat.Builder(context, HOME_MONITOR_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle(context.getString(R.string.monitor_notification_title))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent)
        .build()
}

fun updateMonitorForegroundNotification(context: Context, text: String) {
    val notification = buildMonitorNotification(context, text)
    NotificationManagerCompat.from(context).notify(MONITOR_FOREGROUND_ID, notification)
}
