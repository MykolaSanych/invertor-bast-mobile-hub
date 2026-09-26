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

        // На цьому пристрої система/лаунчер кличе onUpdate() раз на ~9-10с - явно
        // частіше за задекларований updatePeriodMillis (поза нашим кодом, OS-квірк).
        // Кожен такий тик запускав повний мережевий опит трьох модулів і перемальовував
        // віджет - зайве навантаження на ESP32 і, разом зі старою "пульс на кожен
        // успіх" логікою, головна причина "віджет постійно блимає". Справжній живий
        // застосунок, поки відкритий, і так оновлює віджет частіше й напряму - фоновий
        // воркер потрібен лише коли застосунок закритий, тож не частіше, ніж раз на
        // MIN_REFRESH_INTERVAL_MS, повторний тик у цьому вікні просто пропускаємо.
        val now = System.currentTimeMillis()
        if (now - lastCompletedAtMs < MIN_REFRESH_INTERVAL_MS) {
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
            lastCompletedAtMs = now
            val suppressPulse = StatusWidgetProvider.shouldSuppressPulse(inputData)
            // Пульс лише на РЕАЛЬНУ зміну даних (не на кожен успішний фоновий тик) -
            // друга половина фіксу "постійно блимає".
            val changed = StatusWidgetProvider.hasChangedSinceLastAutoRefresh(status)
            StatusWidgetProvider.updateAllWidgets(
                applicationContext,
                status,
                triggerPulse = !suppressPulse && changed,
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
        private const val MIN_REFRESH_INTERVAL_MS = 20_000L

        @Volatile
        private var lastCompletedAtMs = 0L
    }
}
