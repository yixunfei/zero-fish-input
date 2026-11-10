package dev.zeroinput.ime.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.zeroinput.ime.R

/** Generic state only. Never accepts task text, arguments, names or endpoints. */
internal class AiRunNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun available(): Boolean = manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    private fun channel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.ai_run_channel), NotificationManager.IMPORTANCE_LOW)
            .apply { description = context.getString(R.string.ai_run_channel_description); setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET })
    }
    fun notification(token: String, state: AiToolRunState): Notification {
        require(token.length in 1..64); channel()
        val open = PendingIntent.getActivity(context, 710,
            Intent(context, AiToolTaskActivity::class.java).setAction(OPEN).setData("zeroinput-task://$token".toUri()).putExtra(TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(context, 711,
            Intent(context, AiTaskStopReceiver::class.java).setAction(STOP).setData("zeroinput-task://$token".toUri()).putExtra(TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val active = state in setOf(AiToolRunState.IDLE, AiToolRunState.THINKING, AiToolRunState.APPROVAL, AiToolRunState.EXECUTING)
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.ai_run_title)).setContentText(context.getString(when (state) {
                AiToolRunState.APPROVAL -> R.string.ai_run_approval
                AiToolRunState.COMPLETED -> R.string.ai_run_finished
                AiToolRunState.FAILED -> R.string.ai_run_failed
                AiToolRunState.STOPPED -> R.string.ai_run_stopped
                else -> R.string.ai_run_running
            })).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active).setAutoCancel(!active)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(if (active) 300_000 else 120_000).apply {
                if (active) addAction(0, context.getString(R.string.ai_run_stop), stop)
            }.build()
    }
    fun show(token: String, state: AiToolRunState): Boolean = try {
        if (!available()) false else { manager.notify(ID, notification(token, state)); true }
    } catch (_: RuntimeException) { false }
    fun clear() = manager.cancel(ID)
    companion object {
        const val ID = 710
        const val CHANNEL = "ai-task-status"
        const val TOKEN = "task_nonce"
        const val OPEN = "dev.zeroinput.ime.ai.OPEN_TASK"
        const val STOP = "dev.zeroinput.ime.ai.STOP_TASK"
    }
}
