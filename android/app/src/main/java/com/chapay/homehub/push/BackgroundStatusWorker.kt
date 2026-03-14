package com.chapay.homehub.push

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chapay.homehub.data.AppConfigStorage
import com.chapay.homehub.data.StatusRepository
import com.chapay.homehub.widget.StatusWidgetProvider

class BackgroundStatusWorker(
    appContext: android.content.Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = StatusRepository(appContext)

    override suspend fun doWork(): Result {
        return runCatching {
            ensureNotificationChannel(applicationContext)

            val config = AppConfigStorage.load(applicationContext)
            val status = repository.fetchUnified(config)
            StatusChangeProcessor.process(
                context = applicationContext,
                status = status,
                config = config,
                emitNotifications = true,
            )
            StatusWidgetProvider.updateAllWidgets(applicationContext, status)
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
