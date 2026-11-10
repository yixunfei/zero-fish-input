package dev.zeroinput.ime.ai

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiRunNotificationsTest {
    @Test fun immutableActionsKeepTheirOriginalNonceAndNotificationsContainOnlyGenericState() {
        val app = RuntimeEnvironment.getApplication()
        val notifications = AiRunNotifications(app)
        val first = notifications.notification("public-first", AiToolRunState.APPROVAL)
        val second = notifications.notification("public-second", AiToolRunState.THINKING)
        val stop = shadowOf(first.actions.single().actionIntent)
        assertEquals("public-first", stop.savedIntent.getStringExtra(AiRunNotifications.TOKEN))
        assertEquals(setOf(AiRunNotifications.TOKEN), stop.savedIntent.extras!!.keySet())
        assertTrue(stop.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertNotEquals(first.actions.single().actionIntent, second.actions.single().actionIntent)
        assertNotEquals(first.contentIntent, second.contentIntent)
        assertEquals(Notification.VISIBILITY_SECRET, first.visibility)
        assertFalse(first.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("public-first"))
        assertEquals(0, notifications.notification("public-first", AiToolRunState.COMPLETED).actions?.size ?: 0)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertFalse(notifications.available()); assertFalse(notifications.show("public-first", AiToolRunState.THINKING))
    }
}
