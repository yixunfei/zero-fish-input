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
                    ?.mapNotNull(::readSelected)
                    ?.toList()
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

        private fun readSelected(notification: StatusBarNotification): NotificationValue? {
            val extras = notification.notification.extras
            return DeviceStage6NotificationSanitizer.sanitize(
                extras.getCharSequence(Notification.EXTRA_TITLE),
                extras.getCharSequence(Notification.EXTRA_TEXT),
            )
        }

        private const val MAX_NOTIFICATIONS_PER_REQUEST = 5
    }
}

/** Bounds platform text before conversion and drops notifications that carry a hard-blocked secret. */
internal object DeviceStage6NotificationSanitizer {
    fun sanitize(title: CharSequence?, text: CharSequence?): NotificationValue? {
        val safeTitle = bounded(title, MAX_TITLE_CHARS).orEmpty()
        val safeText = bounded(text, MAX_TEXT_CHARS).orEmpty()
        if (safeTitle.isBlank() && safeText.isBlank()) return null
        if (DeviceAgentSafety.sensitiveValue(safeTitle, MAX_TITLE_CHARS) ||
            DeviceAgentSafety.sensitiveValue(safeText, MAX_TEXT_CHARS) ||
            DeviceAgentSafety.sensitiveLabel(safeTitle, MAX_TITLE_CHARS) ||
            DeviceAgentSafety.sensitiveLabel(safeText, MAX_TEXT_CHARS)) return null
        return NotificationValue(safeTitle, safeText)
    }

    private fun bounded(value: CharSequence?, limit: Int): String? {
        if (value == null || value.length == 0) return null
        if (value.length <= limit) return value.toString()
        // Never cut between a high and low surrogate: the orphaned high surrogate
        // is not valid UTF-16 and cannot be serialized as a clean provider string.
        val end = if (limit > 0 && Character.isHighSurrogate(value[limit - 1])) limit - 1 else limit
        return value.subSequence(0, end).toString()
    }

    private const val MAX_TITLE_CHARS = 256
    private const val MAX_TEXT_CHARS = 1_024
}

internal data class NotificationValue(val title: String, val text: String) {
    override fun toString(): String = "NotificationValue(redacted)"
}

internal object DeviceStage6NotificationStore {
    fun findForPackage(packageName: String): List<NotificationValue> =
        DeviceStage6NotificationListenerService.findForPackage(packageName)
}
