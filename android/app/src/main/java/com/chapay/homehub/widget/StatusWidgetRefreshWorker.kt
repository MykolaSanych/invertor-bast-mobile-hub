package com.chapay.homehub.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chapay.homehub.data.AppConfigStorage
import com.chapay.homehub.data.StatusRepository
import com.chapay.homehub.push.StatusChangeProcessor

class StatusWidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = StatusRepository(appContext)

    override suspend fun doWork(): Result {
        if (!StatusWidgetProvider.hasActiveWidgets(applicationContext)) {
            return Result.success()
        }

        return runCatching {
            val config = AppConfigStorage.load(applicationContext)
            val status = repository.fetchUnified(config)
            StatusChangeProcessor.process(
                context = applicationContext,
                status = status,
                config = config,
                emitNotifications = false,
            )
            val suppressPulse = StatusWidgetProvider.shouldSuppressPulse(inputData)
            StatusWidgetProvider.updateAllWidgets(
                applicationContext,
                status,
                triggerPulse = !suppressPulse,
            )
            Result.success()
        }.getOrElse { error ->
            Log.w(TAG, "Widget refresh failed: ${error.message}")
            Result.retry()
        }
    }

    private companion object {
        private const val TAG = "HomeHubWidgetWorker"
    }
}
