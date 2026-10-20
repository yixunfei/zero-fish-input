package dev.zeroinput.ime.ai.device

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.atomic.AtomicReference

/** System-bound reader; it retains no notification text or history between requests. */
class DeviceStage6NotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        active.set(this)
    }

    override fun onListenerDisconnected() {
        active.compareAndSet(this, null)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        active.compareAndSet(this, null)
        super.onDestroy()
    }

    companion object {
        private val active = AtomicReference<DeviceStage6NotificationListenerService?>()

        internal fun findForPackage(packageName: String): List<NotificationValue> {
            val service = active.get() ?: return emptyList()
            return runCatching {
                service.getActiveNotifications()
                    ?.asSequence()
                    ?.filter { it.packageName == packageName }
                    ?.take(MAX_NOTIFICATIONS_PER_REQUEST)
                    ?.map(::readSelected)
                    ?.toList()
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

        private fun readSelected(notification: StatusBarNotification): NotificationValue {
            val extras = notification.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            return NotificationValue(title.take(256), text.take(1024))
        }

        private const val MAX_NOTIFICATIONS_PER_REQUEST = 5
    }
}

internal data class NotificationValue(val title: String, val text: String) {
    override fun toString(): String = "NotificationValue(redacted)"
}

internal object DeviceStage6NotificationStore {
    fun findForPackage(packageName: String): List<NotificationValue> =
        DeviceStage6NotificationListenerService.findForPackage(packageName)
}
