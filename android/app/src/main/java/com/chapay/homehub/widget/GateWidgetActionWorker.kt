package com.chapay.homehub.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chapay.homehub.data.AppConfigStorage
import com.chapay.homehub.data.StatusRepository

class GateWidgetActionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = StatusRepository(appContext)

    override suspend fun doWork(): Result {
        return runCatching {
            val config = AppConfigStorage.load(applicationContext)
            if (config.garageEnabled) {
                repository.triggerGate(config)
            }
            val status = repository.fetchUnified(config)
            StatusWidgetProvider.updateAllWidgets(applicationContext, status)
            Result.success()
        }.getOrElse { error ->
            Log.w(TAG, "Gate widget action failed: ${error.message}")
            runCatching {
                val config = AppConfigStorage.load(applicationContext)
                val status = repository.fetchUnified(config)
                StatusWidgetProvider.updateAllWidgets(applicationContext, status, triggerPulse = false)
            }
            Result.success()
        }
    }

    companion object {
        private const val TAG = "GateWidgetActionWorker"
        private const val UNIQUE_WORK_NAME = "homehub_gate_widget_action"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<GateWidgetActionWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
