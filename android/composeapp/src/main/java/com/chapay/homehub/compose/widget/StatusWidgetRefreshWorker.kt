package com.chapay.homehub.compose.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chapay.homehub.compose.data.AppConfigStorage
import com.chapay.homehub.compose.data.StatusRepository

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
        private const val TAG = "HomeHubComposeWidget"
    }
}
