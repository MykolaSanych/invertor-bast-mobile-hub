package com.chapay.homehub.compose.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chapay.homehub.compose.data.AppConfigStorage

/** Перезапускає фоновий сервіс стеження після перезавантаження пристрою. */
class BootRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppConfigStorage.load(context).backgroundServiceEnabled) return
        EventPollingServiceController.start(context)
    }
}
