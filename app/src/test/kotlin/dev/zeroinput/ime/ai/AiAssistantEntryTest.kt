package dev.zeroinput.ime.ai

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiAssistantEntryTest {
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test fun launcherAcceptsOnlyTheContentFreeLauncherShape() {
        assertTrue(AiAssistantLauncherActivity.accepts(launcher()))
        assertFalse(AiAssistantLauncherActivity.accepts(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "public sample")))
        assertFalse(AiAssistantLauncherActivity.accepts(launcher().setData(Uri.parse("content://fixture/item"))))
        assertFalse(AiAssistantLauncherActivity.accepts(launcher().setType("text/plain")))
        assertFalse(AiAssistantLauncherActivity.accepts(launcher().addCategory(Intent.CATEGORY_DEFAULT)))
        assertFalse(AiAssistantLauncherActivity.accepts(Intent()))
    }

    @Test fun launcherNeverForwardsIncomingTaskContentOrUriGrants() {
        val incoming = launcher().putExtra(Intent.EXTRA_TEXT, "public sample")
            .putExtra("task", "do not execute")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        incoming.clipData = android.content.ClipData.newPlainText("public", "public sample")
        val activity = Robolectric.buildActivity(AiAssistantLauncherActivity::class.java, incoming).create().get()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(AiAssistantActivity::class.java.name, next.component?.className)
        assertNull(next.extras)
        assertNull(next.clipData)
        assertNull(next.data)
        assertEquals(0, next.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(activity.isFinishing)
    }

    @Test fun externalSendCannotOpenThePrivateChat() {
        val incoming = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "public sample")
        val activity = Robolectric.buildActivity(AiAssistantLauncherActivity::class.java, incoming).create().get()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }
}
