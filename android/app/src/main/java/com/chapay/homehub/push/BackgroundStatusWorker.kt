package com.chapay.homehub.push

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chapay.homehub.data.AppConfigStorage
import com.chapay.homehub.data.StatusRepository

class BackgroundStatusWorker(
    appContext: android.content.Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = StatusRepository()

    override suspend fun doWork(): Result {
        return runCatching {
            ensureNotificationChannel(applicationContext)

            val config = AppConfigStorage.load(applicationContext)
            val status = repository.fetchUnified(config)

            val current = StatusSnapshot.fromUnified(status)
            val previous = StatusSnapshotStore.load(applicationContext)
            if (previous != null) {
                LocalEventEngine.detect(previous, current, config).forEach { event ->
                    showPushNotification(applicationContext, event.title, event.body)
                }
            }

            StatusSnapshotStore.save(applicationContext, current)
            Result.success()
        }.getOrElse { error ->
            Log.w(TAG, "Background poll failed: ${error.message}")
            Result.retry()
        }
    }

    private companion object {
        private const val TAG = "HomeHubBgWorker"
    }
}
