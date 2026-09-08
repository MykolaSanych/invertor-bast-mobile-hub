package com.chapay.homehub.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackgroundMonitorScheduler {
    private const val CHAIN_WORK_NAME = "home_background_poll_chain"
    private const val ONE_TIME_WORK_NAME = "home_background_poll_once"

    // PeriodicWorkRequest не може оновлюватись частіше ніж раз на 15 хв -
    // це жорсткий мінімум самого WorkManager, обійти його неможливо. Замість
    // періодичної задачі - ланцюжок одноразових: кожен успішний запуск сам
    // планує наступний (BackgroundStatusWorker.scheduleNext()), тому
    // інтервал можна зробити коротшим.
    const val CHAIN_INTERVAL_MINUTES = 5L

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // Викликається при кожному застосуванні конфігурації (відкриття
    // застосунку, зміна налаштувань) - ExistingWorkPolicy.KEEP означає, що
    // це не переривє вже запущений ланцюжок, а лише запускає його, якщо він
    // ще жодного разу не стартував (свіже встановлення) або обірвався.
    fun ensureScheduled(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackgroundStatusWorker>()
            .setConstraints(constraints())
            .setInitialDelay(CHAIN_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            CHAIN_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    // Викликає сам BackgroundStatusWorker після успішного завершення, щоб
    // запланувати наступний тік ланцюжка через CHAIN_INTERVAL_MINUTES від
    // ЦЬОГО моменту (REPLACE - навмисно, щоб імовірний паралельний runNow()
    // не створював дублікат, а просто зсував наступний тік на себе).
    fun scheduleNext(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackgroundStatusWorker>()
            .setConstraints(constraints())
            .setInitialDelay(CHAIN_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            CHAIN_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackgroundStatusWorker>()
            .setConstraints(constraints())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
