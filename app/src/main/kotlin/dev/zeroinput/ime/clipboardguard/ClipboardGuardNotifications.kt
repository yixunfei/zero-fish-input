package dev.zeroinput.ime.clipboardguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.zeroinput.ime.R

internal class ClipboardGuardNotifications(private val context: Context) {
    private var target: PendingIntent? = null

    fun allowed(): Boolean = (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun show(state: ClipboardGuardState) {
        cancel()
        if (!state.options.notificationReminder || !allowed()) return
        val ticket = state.ticket
        val intent = if (ticket != null && state.options.clearMode != ClipboardClearMode.NONE) {
            Intent(context, ClipboardClearActivity::class.java).putExtra(ClipboardClearActivity.EXTRA_TICKET, ticket.id)
        } else Intent(context, ClipboardGuardSettingsActivity::class.java)
        intent.action = ticket?.id ?: "clipboard-guard-status"
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        target = pending
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.clipboard_guard_notifications), NotificationManager.IMPORTANCE_DEFAULT,
        ))
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_delete)
            .setContentTitle(context.getString(R.string.clipboard_guard_title))
            .setContentText(context.getString(state.statusText()))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            if (allowed()) manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            cancel()
        }
    }

    fun cancel() {
        target?.cancel()
        target = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL = "system-clipboard-changes"
        const val NOTIFICATION_ID = 610
        const val REQUEST_CODE = 610
    }
}
