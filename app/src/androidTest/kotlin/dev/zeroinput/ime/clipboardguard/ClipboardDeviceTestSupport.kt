package dev.zeroinput.ime.clipboardguard

import android.app.AppOpsManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.R
import dev.zeroinput.ime.testing.InputFixtureActivity
import org.junit.Assert.assertTrue

internal object ClipboardDeviceTestSupport {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext

    init {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    fun await(message: String = "Public fixture flow did not settle", condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue(message, condition())
    }

    fun flushGuardPreferences() {
        check(context.getSharedPreferences("clipboard-guard-settings", android.content.Context.MODE_PRIVATE).edit().commit())
    }

    fun showFixtureKeyboard(activity: InputFixtureActivity) {
        var nextRequestAt = 0L
        await("Fixture keyboard did not become visible") {
            var focused = false
            onMain { focused = activity.hasWindowFocus() && activity.editor.hasFocus() }
            val visible = focused && instrumentation.uiAutomation.windows.any {
                it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.packageName == context.packageName
            }
            val now = SystemClock.elapsedRealtime()
            if (focused && !visible && now >= nextRequestAt) {
                // Instrumentation restarts the IME process. Android can retain its old binding
                // until a later input/show request triggers the platform reconnect timeout.
                onMain {
                    activity.getSystemService(InputMethodManager::class.java).apply {
                        restartInput(activity.editor)
                        showSoftInput(activity.editor, 0)
                    }
                }
                nextRequestAt = now + 250
            }
            visible
        }
    }

    fun startSource() {
        val component = ComponentName(instrumentation.context.packageName, ExternalClipboardFixture::class.java.name)
        shell("am start -W -n ${component.flattenToString()} -f 0x10008000")
        await { findText("Copy public fixture") != null }
        await { findText("Source ready")?.window?.isFocused == true }
        @Suppress("DEPRECATION")
        val source = context.packageManager.getApplicationInfo(instrumentation.context.packageName, 0)
        assertTrue("The source must have a different UID", source.uid != Process.myUid())
    }

    fun roots(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) instrumentation.uiAutomation.clearCache()
        return listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) +
            instrumentation.uiAutomation.windows.mapNotNull { it.root }
    }

    fun findText(text: String): AccessibilityNodeInfo? = roots().firstNotNullOfOrNull { root ->
        root.findAccessibilityNodeInfosByText(text).firstOrNull { it.text?.toString() == text }
    }

    fun click(text: String) {
        await { findText(text) != null }
        var node = checkNotNull(findText(text))
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    fun closeSource() {
        // A process-exit action cannot acknowledge an accessibility IPC after execution.
        checkNotNull(findText("Close public fixture")).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await { findText("Close public fixture") == null }
    }

    fun longPressPublicText(label: String = "public guard fixture") {
        instrumentation.uiAutomation.waitForIdle(100, 6_000)
        val bounds = settledPublicTextBounds(label)
        val x = bounds.left + minOf(bounds.width() / 2, (32 * context.resources.displayMetrics.density).toInt())
        val y = bounds.centerY()
        val downTime = SystemClock.uptimeMillis()
        injectTouch(MotionEvent.ACTION_DOWN, downTime, x, y)
        try {
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 300L)
        } finally {
            injectTouch(MotionEvent.ACTION_UP, downTime, x, y)
        }
    }

    private fun settledPublicTextBounds(label: String): Rect {
        val bounds = Rect()
        var stableSince = SystemClock.elapsedRealtime()
        await("Public text bounds must settle in the focused source window") {
            val text = findText(label)
            val next = Rect()
            text?.getBoundsInScreen(next)
            val ready = text?.packageName == instrumentation.context.packageName &&
                text.isVisibleToUser && text.window?.isFocused == true && !next.isEmpty
            if (!ready || next != bounds) {
                bounds.set(next)
                stableSince = SystemClock.elapsedRealtime()
            }
            ready && SystemClock.elapsedRealtime() - stableSince >= 250
        }
        return bounds
    }

    private fun injectTouch(action: Int, downTime: Long, x: Int, y: Int) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            // The public synchronous API waits for window animations and surface input updates.
            assertTrue("Public gesture must be delivered", instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally { event.recycle() }
    }

    fun overlayWindows(): List<AccessibilityWindowInfo> = instrumentation.uiAutomation.windows.filter {
        it.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
            it.root?.findAccessibilityNodeInfosByText(context.getString(R.string.clipboard_guard_title))?.isNotEmpty() == true
    }

    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    @Suppress("DEPRECATION")
    fun withOverlayPermission(granted: Boolean, test: () -> Unit) {
        val manager = context.getSystemService(AppOpsManager::class.java)
        val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName)
        } else manager.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName)
        try {
            setOverlayPermission(if (granted) "allow" else "deny")
            test()
        } finally {
            setOverlayPermission(when (original) {
                AppOpsManager.MODE_ALLOWED -> "allow"
                AppOpsManager.MODE_IGNORED -> "ignore"
                AppOpsManager.MODE_ERRORED -> "deny"
                AppOpsManager.MODE_FOREGROUND -> "foreground"
                else -> "default"
            })
        }
    }

    fun setOverlayPermission(mode: String) {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode")
    }
}
