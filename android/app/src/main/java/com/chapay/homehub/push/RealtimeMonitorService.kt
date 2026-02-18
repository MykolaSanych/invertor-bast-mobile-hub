package com.chapay.homehub.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.chapay.homehub.data.AppConfig
import com.chapay.homehub.data.AppConfigStorage
import com.chapay.homehub.data.StatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RealtimeMonitorService : Service() {
    private val repository = StatusRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        startForeground(
            MONITOR_FOREGROUND_ID,
            buildMonitorNotification(this, "Realtime monitoring starting..."),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { runPollingLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runPollingLoop() {
        while (scope.isActive) {
            val config = AppConfigStorage.load(this@RealtimeMonitorService)
            if (!config.realtimeMonitorEnabled) {
                stopSelf()
                break
            }

            runCatching { pollOnce(config) }
                .onFailure { error ->
                    Log.w(TAG, "Realtime poll failed: ${error.message}")
                }

            updateMonitorForegroundNotification(
                context = this@RealtimeMonitorService,
                text = "Monitoring every ${config.realtimePollIntervalSec}s",
            )
            delay(config.realtimePollIntervalSec.coerceIn(3, 60) * 1000L)
        }
    }

    private suspend fun pollOnce(config: AppConfig) {
        val status = repository.fetchUnified(config)
        val current = StatusSnapshot.fromUnified(status)
        val previous = StatusSnapshotStore.load(this)
        if (previous != null) {
            LocalEventEngine.detect(previous, current, config).forEach { event ->
                showPushNotification(this, event.title, event.body)
            }
        }
        StatusSnapshotStore.save(this, current)
    }

    companion object {
        private const val TAG = "HomeHubRealtimeSvc"

        fun start(context: Context) {
            val intent = Intent(context, RealtimeMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RealtimeMonitorService::class.java))
        }
    }
}
