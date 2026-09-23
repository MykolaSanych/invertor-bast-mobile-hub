package com.chapay.homehub.compose.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chapay.homehub.compose.data.AppConfigStorage
import com.chapay.homehub.compose.data.StatusRepository
import com.chapay.homehub.compose.data.UnifiedStatus

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
            // fetchUnified() ніколи не кидає виняток - при мережевій помилці
            // вона сама повертає порожній UnifiedStatus() (усі поля null).
            // На цьому пристрої фонові запити з Worker-а (на відміну від
            // живого опитування, поки застосунок відкрито) регулярно
            // блокуються агресивним керуванням батареєю/мережею OEM - без
            // цієї перевірки такий "порожній успіх" перетирав на віджеті
            // щойно показані реальні дані рисками. Пропускаємо оновлення
            // замість цього - нехай лишаються останні реальні дані.
            if (!hasAnyModuleData(status)) {
                Log.w(TAG, "Widget refresh returned empty status (likely network/background restriction); skipping render")
                return@runCatching Result.retry()
            }
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

    private fun hasAnyModuleData(status: UnifiedStatus): Boolean {
        return status.inverter != null || status.loadController != null || status.garage != null
    }

    private companion object {
        private const val TAG = "HomeHubComposeWidget"
    }
}
