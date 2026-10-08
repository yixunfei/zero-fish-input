package dev.zeroinput.ime.ai.page

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import dev.zeroinput.ime.ZeroInputApplication

/** The only production adapter allowed to retrieve external accessibility nodes. */
class PageReferenceService : AccessibilityService() {
    private val graph get() = (application as ZeroInputApplication).graph
    private var registration: AutoCloseable? = null
    private var invalidationObserver: AutoCloseable? = null
    @Volatile private var sourceWindow = -1
    @Volatile private var sourcePackage: String? = null
    private var receiverRegistered = false
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { revoke() }
    }

    override fun onServiceConnected() {
        if (Build.VERSION.SDK_INT >= 33) setCacheEnabled(false)
        registration?.close()
        registration = graph.pageReferences.attach(PageTextSource(::capture))
        invalidationObserver?.close()
        invalidationObserver = graph.pageReferences.observeInvalidation {
            sourceWindow = -1
            sourcePackage = null
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (sourceWindow == -1 || event == null) return
        // Metadata only: never inspect event.text, event.source, or a window's root here.
        // A package/window ID can be reused after navigation; it cannot prove the page is unchanged.
        if (invalidatesReview(event.eventType)) revoke()
    }

    override fun onInterrupt() { revoke() }

    override fun onUnbind(intent: Intent?): Boolean {
        registration?.close()
        registration = null
        invalidationObserver?.close()
        invalidationObserver = null
        revoke()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        invalidationObserver?.close()
        invalidationObserver = null
        registration?.close()
        registration = null
        if (receiverRegistered) unregisterReceiver(lockReceiver)
        receiverRegistered = false
        revoke()
        super.onDestroy()
    }

    private fun revoke() {
        sourceWindow = -1
        sourcePackage = null
        graph.pageReferences.invalidate()
    }

    internal companion object {
        fun invalidatesReview(eventType: Int): Boolean =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    private fun capture(expectedPackage: String, current: () -> Boolean): PageTextSnapshot {
        check(expectedPackage != packageName && expectedPackage != "android" && current() && unlocked())
        val all = windows
        try {
            check(all.size <= 16)
            val window = all.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
                ?: error("Page unavailable")
            val bounds = Rect().also(window::getBoundsInScreen)
            val occlusions = all.filter { it.id != window.id && it.layer > window.layer }
                .map { Rect().also(it::getBoundsInScreen) }
            val root = if (Build.VERSION.SDK_INT >= 33) window.getRoot(0) else window.root
            checkNotNull(root) { "Page unavailable" }
            sourceWindow = window.id
            sourcePackage = expectedPackage
            val node = AndroidNode(root, expectedPackage, bounds, occlusions)
            val snapshot = PageTextCollector.collect(node) {
                current() && sourceWindow == window.id && sourcePackage == expectedPackage && unlocked()
            }
            check(current() && sourceStillFocused(window.id, expectedPackage) && unlocked()) { "Page expired" }
            return snapshot
        } finally { all.forEach(::releaseWindow) }
    }

    private fun unlocked() = getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false

    private fun sourceStillFocused(id: Int, expectedPackage: String): Boolean {
        if (!unlocked()) return false
        val all = windows
        return try {
            all.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused } == 1 &&
                all.any { it.id == id && it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.isFocused && it.root?.packageName?.toString() == expectedPackage }
        } finally { all.forEach(::releaseWindow) }
    }

    @Suppress("DEPRECATION")
    private fun releaseWindow(window: AccessibilityWindowInfo) { window.recycle() }

    private class AndroidNode(
        private val node: AccessibilityNodeInfo,
        private val expectedPackage: String,
        private val windowBounds: Rect,
        private val occlusions: List<Rect>,
    ) : PageTextNode {
        override val excluded: Boolean get() = !node.isVisibleToUser || node.isEditable || node.isPassword ||
            node.packageName?.toString() != expectedPackage ||
            (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
        override val fullyVisible: Boolean get() {
            val bounds = Rect().also(node::getBoundsInScreen)
            return !bounds.isEmpty && windowBounds.contains(bounds) && occlusions.none { Rect.intersects(it, bounds) }
        }
        override val text: CharSequence? get() = node.text
        override val childCount: Int get() = node.childCount
        override fun child(index: Int): PageTextNode? {
            val child = if (Build.VERSION.SDK_INT >= 33) node.getChild(index, 0) else node.getChild(index)
            return child?.let { AndroidNode(it, expectedPackage, windowBounds, occlusions) }
        }
        @Suppress("DEPRECATION")
        override fun close() { node.recycle() }
    }
}
