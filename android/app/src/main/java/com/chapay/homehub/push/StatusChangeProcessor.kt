package com.chapay.homehub.push

import android.content.Context
import com.chapay.homehub.data.AppConfig
import com.chapay.homehub.data.UnifiedStatus

object StatusChangeProcessor {
    fun process(
        context: Context,
        status: UnifiedStatus,
        config: AppConfig,
        emitNotifications: Boolean,
    ) {
        val current = StatusSnapshot.fromUnified(status)
        val previous = StatusSnapshotStore.load(context)
        if (previous != null) {
            LocalEventEngine.detect(context, previous, current, config).forEach { event ->
                EventJournalStore.append(
                    context = context,
                    title = event.title,
                    body = event.body,
                    atMs = event.atMs,
                    severity = event.severity,
                    kind = event.kind,
                    module = event.module,
                )
                if (emitNotifications && event.sendNotification) {
                    showPushNotification(context, event.title, event.body)
                }
            }
        }

        StatusSnapshotStore.save(context, current)
        RollingStatusHistoryStore.append(context, status, status.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis())
    }
}
