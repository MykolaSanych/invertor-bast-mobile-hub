package com.chapay.homehub.push

import android.content.Context
import com.chapay.homehub.data.AppConfig
import com.chapay.homehub.data.AppConfigStorage

object MonitorController {
    fun applyConfig(context: Context, config: AppConfig, runImmediateWorker: Boolean) {
        ensureNotificationChannel(context)
        BackgroundMonitorScheduler.ensureScheduled(context)
        if (runImmediateWorker) {
            BackgroundMonitorScheduler.runNow(context)
        }

        if (config.realtimeMonitorEnabled) {
            RealtimeMonitorService.start(context)
        } else {
            RealtimeMonitorService.stop(context)
        }
    }

    fun applyStoredConfig(context: Context, runImmediateWorker: Boolean) {
        val config = AppConfigStorage.load(context)
        applyConfig(context, config, runImmediateWorker)
    }
}
