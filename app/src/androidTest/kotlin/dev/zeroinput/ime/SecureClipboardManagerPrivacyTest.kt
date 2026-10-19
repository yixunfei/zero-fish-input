package dev.zeroinput.ime

import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.settings.SecureClipboardManagerActivity
import dev.zeroinput.ime.settings.SecureClipboardManagerView
import dev.zeroinput.ime.auth.AuthenticationBroker
import dev.zeroinput.ime.auth.SecureClipboardUnlockActivity
import dev.zeroinput.security.AuthenticationGrant
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

@SdkSuppress(minSdkVersion = 29)
class SecureClipboardManagerPrivacyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph

    @Test fun privateFieldsDisableCaptureAndClearOnDismiss() = withActivity { activity ->
        lateinit var fields: List<EditText>
        onMain {
            val screen = views(activity.window.decorView).filterIsInstance<SecureClipboardManagerView>().single()
            screen.onAddRequested()
            val root = WindowInspector.getGlobalWindowViews().single {
                views(it).filterIsInstance<EditText>().size == 2
            }
            fields = views(root).filterIsInstance<EditText>()
            fields.forEach { it.setText("public fixture") }
            assertTrue((root.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            fields.forEach { assertFalse(it.isSaveEnabled) }
            root.findViewById<View>(android.R.id.button2).performClick()
        }
        // Dialog dismissal dispatches its listener through the main looper.
        instrumentation.waitForIdleSync()
        onMain { fields.forEach { assertEquals("", it.text.toString()) } }
    }

    @Test fun metadataViewDisablesAutofillAndContentCapture() = withActivity { activity ->
        onMain {
            val screen = views(activity.window.decorView).filterIsInstance<SecureClipboardManagerView>().single()
            assertFalse(screen.isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, screen.importantForAutofill)
            if (Build.VERSION.SDK_INT >= 30) {
                assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, screen.importantForContentCapture)
            }
        }
    }

    @Test fun deleteConfirmationProtectsItsLabelAndClosesWithThePage() = withActivity { activity ->
        lateinit var dialogRoot: View
        onMain {
            val screen = views(activity.window.decorView).filterIsInstance<SecureClipboardManagerView>().single()
            screen.onDeleteRequested(dev.zeroinput.userdata.SecureClipboardMetadata("fixture", "public label", 3, 0))
            dialogRoot = WindowInspector.getGlobalWindowViews().single { it.findViewById<View>(android.R.id.button1) != null }
            assertTrue((dialogRoot.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, dialogRoot.importantForAutofill)
            if (Build.VERSION.SDK_INT >= 30) {
                assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, dialogRoot.importantForContentCapture)
            }
            activity.finish()
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        var detached = false
        while (!detached && android.os.SystemClock.uptimeMillis() < deadline) {
            onMain { detached = !dialogRoot.isAttachedToWindow }
            if (!detached) android.os.SystemClock.sleep(20)
        }
        assertTrue("Private dialog must leave the window with its Activity", detached)
    }

    @Test fun cancelledAdditionalAuthenticationClosesThePageAndNeverRestoresItsLabels() {
        val entry = graph.secureClipboard.add("public cancellation fixture", "public fixture",
            AuthenticationGrant.afterSuccessfulSystemAuthentication())
        try {
            withActivity { activity ->
                val requestId = AtomicReference<String>()
                val monitor = object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                        if (intent.component?.className != SecureClipboardUnlockActivity::class.java.name) return null
                        requestId.set(intent.getStringExtra(SecureClipboardUnlockActivity.EXTRA_REQUEST_ID))
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                }
                instrumentation.addMonitor(monitor)
                try {
                    lateinit var screen: SecureClipboardManagerView
                    onMain {
                        screen = views(activity.window.decorView).filterIsInstance<SecureClipboardManagerView>().single()
                        screen.onEnableRequested()
                        AuthenticationBroker.complete(checkNotNull(requestId.getAndSet(null)),
                            AuthenticationGrant.afterSuccessfulSystemAuthentication())
                    }
                    awaitOnMain { views(screen).filterIsInstance<android.widget.TextView>().any { it.text == entry.label } }
                    onMain {
                        screen.onDeleteRequested(entry)
                        val dialog = WindowInspector.getGlobalWindowViews().single {
                            it.findViewById<View>(android.R.id.button1) != null
                        }
                        dialog.findViewById<View>(android.R.id.button1).performClick()
                        instrumentation.callActivityOnStop(activity)
                        AuthenticationBroker.complete(checkNotNull(requestId.get()), null)
                    }
                    awaitOnMain { activity.isFinishing }
                    onMain {
                        assertFalse(views(screen).filterIsInstance<android.widget.TextView>().any { it.text == entry.label })
                    }
                } finally { instrumentation.removeMonitor(monitor) }
            }
        } finally {
            graph.secureClipboard.remove(entry.id, AuthenticationGrant.afterSuccessfulSystemAuthentication())
        }
    }

    private fun awaitOnMain(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        var complete = false
        while (!complete && android.os.SystemClock.uptimeMillis() < deadline) {
            onMain { complete = condition() }
            if (!complete) android.os.SystemClock.sleep(20)
        }
        assertTrue("Manager state did not settle", complete)
    }

    private fun withActivity(test: (SecureClipboardManagerActivity) -> Unit) {
        val enabled = graph.settings.secureClipboardEnabled
        onMain { graph.settings.secureClipboardEnabled = false }
        var activity: SecureClipboardManagerActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SecureClipboardManagerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SecureClipboardManagerActivity
            test(activity)
        } finally {
            onMain { activity?.finish(); graph.settings.secureClipboardEnabled = enabled }
            instrumentation.waitForIdleSync()
        }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
