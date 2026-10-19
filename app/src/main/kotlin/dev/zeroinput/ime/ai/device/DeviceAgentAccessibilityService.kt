package dev.zeroinput.ime.ai.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.zeroinput.ai.api.DeviceActionCall
import dev.zeroinput.ai.api.DeviceActionResult
import dev.zeroinput.ai.api.DeviceActionResultStatus
import dev.zeroinput.ai.api.DeviceBounds
import dev.zeroinput.ai.api.DeviceElement
import dev.zeroinput.ai.api.DeviceScreenSnapshot
import dev.zeroinput.ai.api.DeviceAgentLimits
import dev.zeroinput.ai.api.DeviceActionArguments
import dev.zeroinput.ai.api.DeviceActionCatalog
import dev.zeroinput.ime.ZeroInputApplication

/** Operation-capable adapter. It is separate from the read-only page reference service. */
class DeviceAgentAccessibilityService : AccessibilityService() {
    private val graph get() = (application as ZeroInputApplication).graph
    private var registration: AutoCloseable? = null
    private var invalidation: AutoCloseable? = null
    private var receiverRegistered = false
    private var overlay: LinearLayout? = null
    private var approval: ((Boolean) -> Unit)? = null
    private val windowPackages = LinkedHashMap<Int, String>()
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            graph.deviceAgentBroker.invalidate()
            clearOverlay()
        }
    }

    override fun onServiceConnected() {
        if (Build.VERSION.SDK_INT >= 33) setCacheEnabled(false)
        registration?.close()
        registration = graph.deviceAgentBroker.attach(Port())
        invalidation?.close()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString()
        if (event.windowId >= 0 && packageName?.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}")) == true) {
            synchronized(windowPackages) {
                windowPackages[event.windowId] = packageName
                while (windowPackages.size > 32) windowPackages.remove(windowPackages.keys.first())
            }
        }
        val identity = metadataWindow()
        val contentChanged = identity != null && event.eventType in CONTENT_EVENTS &&
            packageName == identity.packageName && event.windowId == identity.windowId
        graph.deviceAgentBroker.observeWindowEvent(identity, contentChanged)
    }

    override fun onInterrupt() {
        graph.deviceAgentBroker.invalidate()
        clearOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        approval = null
        clearOverlay()
        invalidation?.close()
        invalidation = null
        registration?.close()
        registration = null
        if (receiverRegistered) unregisterReceiver(lockReceiver)
        receiverRegistered = false
        graph.deviceAgentBroker.invalidate()
    }

    private fun clearOverlay() {
        approval = null
        overlay?.let { view -> runCatching { getSystemService(WindowManager::class.java)?.removeView(view) } }
        overlay = null
    }

    private fun buildOverlay(title: String, detail: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundColor(0xEE202124.toInt())
        addView(TextView(this@DeviceAgentAccessibilityService).apply {
            text = title
            setTextColor(0xffffffff.toInt())
            textSize = 16f
        })
        addView(android.widget.ScrollView(this@DeviceAgentAccessibilityService).apply {
            layoutParams = LinearLayout.LayoutParams(-1, if (detail.length > 200) dp(160) else -2)
            addView(TextView(this@DeviceAgentAccessibilityService).apply {
                text = detail
                setTextColor(0xffe8eaed.toInt())
                textSize = 14f
                isSaveEnabled = false
            })
        })
    }

    private fun showOverlay(title: String, detail: String, decision: ((Boolean) -> Unit)?) {
        clearOverlay()
        val view = buildOverlay(title, detail)
        if (decision != null) {
            val buttons = LinearLayout(this).apply {
                gravity = Gravity.END
                addView(Button(this@DeviceAgentAccessibilityService).apply {
                    text = getString(dev.zeroinput.ime.R.string.ai_device_agent_decline)
                    setOnClickListener { val callback = approval; clearOverlay(); callback?.invoke(false) }
                })
                addView(Button(this@DeviceAgentAccessibilityService).apply {
                    text = getString(dev.zeroinput.ime.R.string.ai_device_agent_approve)
                    setOnClickListener { val callback = approval; clearOverlay(); callback?.invoke(true) }
                })
            }
            view.addView(buttons)
        } else {
            view.addView(Button(this@DeviceAgentAccessibilityService).apply {
                text = getString(dev.zeroinput.ime.R.string.ai_device_agent_stop)
                setOnClickListener { graph.deviceAgentCoordinator.stop() }
            })
        }
        approval = decision
        overlay = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        try { checkNotNull(getSystemService(WindowManager::class.java)).addView(view, params) }
        catch (failure: RuntimeException) { clearOverlay(); throw failure }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class Port : DeviceAgentAccessibilityPort {
        override fun currentWindow(): DeviceWindowIdentity? = metadataWindow()

        override fun unlocked(): Boolean = isDeviceUnlocked()

        override fun snapshot(includeScreenshot: Boolean, current: () -> Boolean): DeviceScreenSnapshot {
            check(unlocked() && current()) { "Device is unavailable" }
            val focused = focusedWindow() ?: error("Application window unavailable")
            try {
                val root = rootOf(focused) ?: error("Application content unavailable")
                return try {
                    val packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() }
                        ?: error("Application package unavailable")
                    val collected = collectElements(root, focused, current)
                    check(current() && unlocked()) { "Device task expired" }
                    val redactions = collected.elements.filter { it.editable || it.sensitive || it.password }
                        .map { Rect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) }
                        .plus(occludedRegions(focused))
                    val screenshot = if (includeScreenshot && Build.VERSION.SDK_INT >= 30 && !collected.incomplete) {
                        captureScreenshot(current, windowBounds(focused), redactions)
                    } else null
                    try {
                        check(current() && unlocked()) { "Device task expired" }
                        DeviceScreenSnapshot(packageName, focused.id, elements = collected.elements,
                            incomplete = collected.incomplete, screenshotJpeg = screenshot)
                    } finally { screenshot?.fill(0) }
                } finally {
                    root.recycle()
                }
            } finally {
                focused.recycle()
            }
        }

        override fun perform(
            call: DeviceActionCall,
            snapshot: DeviceScreenSnapshot,
            current: () -> Boolean,
        ): DeviceActionResult {
            check(current() && unlocked()) { "Device task expired" }
            val focused = focusedWindow() ?: return failed(call, "Application window unavailable")
            try {
                if (focused.id != snapshot.windowId || windowPackage(focused) != snapshot.packageName) return expired(call)
                val root = rootOf(focused) ?: return failed(call, "Application content unavailable")
                return try {
                    val packageName = root.packageName?.toString()
                    if (packageName != snapshot.packageName) return expired(call)
                    execute(call, snapshot, root, current)
                } finally {
                    root.recycle()
                }
            } finally {
                focused.recycle()
            }
        }

        override fun presentApproval(title: String, details: String, decide: (Boolean) -> Unit) {
            showOverlay(title, details, decide)
        }

        override fun showStatus(message: String) { showOverlay(getString(dev.zeroinput.ime.R.string.ai_device_agent_running), message, null) }

        override fun clearOverlay() { this@DeviceAgentAccessibilityService.clearOverlay() }
    }

    private fun execute(
        call: DeviceActionCall,
        snapshot: DeviceScreenSnapshot,
        root: AccessibilityNodeInfo,
        current: () -> Boolean,
    ): DeviceActionResult {
        if (!current()) return expired(call)
        val definition = DeviceActionCatalog.find(call.toolName) ?: return rejected(call, "Unsupported action")
        if (!DeviceActionArguments.valid(definition, call.arguments)) return rejected(call, "Invalid action arguments")
        return when (call.toolName) {
            "wait" -> {
                val milliseconds = call.arguments["milliseconds"]?.toLongOrNull()
                    ?: return failed(call, "Wait duration is invalid")
                if (milliseconds !in 0L..5_000L) return rejected(call, "Wait duration is outside the safe range")
                val deadline = SystemClock.elapsedRealtime() + milliseconds
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!current()) return expired(call)
                    try {
                        Thread.sleep(minOf(50L, deadline - SystemClock.elapsedRealtime()))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return expired(call)
                    }
                }
                success(call, "Wait completed")
            }
            "back" -> global(call, GLOBAL_ACTION_BACK)
            "home" -> global(call, GLOBAL_ACTION_HOME)
            "click" -> nodeAction(call, snapshot, root, AccessibilityNodeInfo.ACTION_CLICK, current)
            "long_click" -> nodeAction(call, snapshot, root, AccessibilityNodeInfo.ACTION_LONG_CLICK, current)
            "scroll" -> scroll(call, snapshot, root, current)
            "input_text" -> inputText(call, snapshot, root, current)
            "ime_action" -> imeAction(call, snapshot, root, current)
            "swipe" -> swipe(call, snapshot, root, current)
            "open_app" -> openApp(call, snapshot.packageName)
            else -> failed(call, "Unsupported action")
        }
    }

    private fun nodeAction(
        call: DeviceActionCall,
        snapshot: DeviceScreenSnapshot,
        root: AccessibilityNodeInfo,
        action: Int,
        current: () -> Boolean,
    ): DeviceActionResult {
        val element = snapshot.elements.firstOrNull { it.ref == call.arguments["element_ref"] }
            ?: return failed(call, "Element not found")
        if ((action == AccessibilityNodeInfo.ACTION_CLICK && !element.clickable) ||
            (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && !element.longClickable) ||
            !element.enabled || element.password || element.sensitive) {
            return rejected(call, "Element is not an approved click target")
        }
        val node = resolve(root, element.ref) ?: return failed(call, "Element expired")
        return try {
            if (!matches(node, element) || !current()) return expired(call)
            if (node.isPassword || sensitive(node)) return rejected(call, "Sensitive element")
            if (action == AccessibilityNodeInfo.ACTION_CLICK && !node.isClickable ||
                action == AccessibilityNodeInfo.ACTION_LONG_CLICK && !node.isLongClickable) {
                return rejected(call, "Element no longer supports this action")
            }
            if (node.performAction(action)) success(call, "Action completed") else failed(call, "Action was not accepted")
        } finally { node.recycle() }
    }

    private fun scroll(
        call: DeviceActionCall,
        snapshot: DeviceScreenSnapshot,
        root: AccessibilityNodeInfo,
        current: () -> Boolean,
    ): DeviceActionResult {
        val element = snapshot.elements.firstOrNull { it.ref == call.arguments["element_ref"] }
            ?: return failed(call, "Scrollable element not found")
        if (!element.scrollable || element.sensitive || !element.enabled) return rejected(call, "Element is not safe to scroll")
        val node = resolve(root, element.ref) ?: return failed(call, "Element expired")
        return try {
            val direction = call.arguments["direction"]
            val action = if (direction in setOf("backward", "up", "left")) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (current() && matches(node, element) && node.isScrollable && !sensitive(node) &&
                node.performAction(action)) success(call, "Scroll completed") else failed(call, "Scroll was not accepted")
        } finally { node.recycle() }
    }

    private fun inputText(
        call: DeviceActionCall,
        snapshot: DeviceScreenSnapshot,
        root: AccessibilityNodeInfo,
        current: () -> Boolean,
    ): DeviceActionResult {
        val element = snapshot.elements.firstOrNull { it.ref == call.arguments["element_ref"] }
            ?: return failed(call, "Editable element not found")
        if (!element.editable || !element.enabled || element.password || element.sensitive) return rejected(call, "Sensitive element")
        val text = call.arguments["text"] ?: return failed(call, "Text is missing")
        val node = resolve(root, element.ref) ?: return failed(call, "Element expired")
        return try {
            if (!current() || !matches(node, element) || !node.isEditable ||
                node.isPassword || sensitive(node)) return rejected(call, "Sensitive element")
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) success(call, "Text entered")
            else failed(call, "Text was not accepted")
        } finally { node.recycle() }
    }

    private fun imeAction(
        call: DeviceActionCall,
        snapshot: DeviceScreenSnapshot,
        root: AccessibilityNodeInfo,
        current: () -> Boolean,
    ): DeviceActionResult {
        val element = snapshot.elements.firstOrNull { it.ref == call.arguments["element_ref"] }
            ?: return failed(call, "Editable element not found")
        if (!element.editable || !element.enabled || element.password || element.sensitive) return rejected(call, "Sensitive element")
        val node = resolve(root, element.ref) ?: return failed(call, "Element expired")
        return try {
            if (Build.VERSION.SDK_INT < 30 || !current() || !matches(node, element) ||
                !node.isEditable || sensitive(node)) return rejected(call, "Editor action unavailable")
            if (!DeviceAgentSafety.safeSearchAction(imeActionLabel(node)) ||
                !DeviceAgentSafety.safeSearchAction(element.imeActionLabel)) return rejected(call, "Editor action is not a labeled search")
            if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) success(call, "Editor action completed")
            else failed(call, "Editor action was not accepted")
        } finally { node.recycle() }
    }

    private fun swipe(
        call: DeviceActionCall, snapshot: DeviceScreenSnapshot, root: AccessibilityNodeInfo, current: () -> Boolean,
    ): DeviceActionResult {
        val element = snapshot.elements.firstOrNull { it.ref == call.arguments["element_ref"] }
            ?: return rejected(call, "Scrollable element not found")
        val node = resolve(root, element.ref) ?: return expired(call)
        try { if (!matches(node, element) || !node.isScrollable) return expired(call) } finally { node.recycle() }
        val startX = call.arguments.getValue("start_x").toInt()
        val startY = call.arguments.getValue("start_y").toInt()
        val endX = call.arguments.getValue("end_x").toInt()
        val endY = call.arguments.getValue("end_y").toInt()
        if (!DeviceAgentSafety.contains(element.bounds, startX, startY) ||
            !DeviceAgentSafety.contains(element.bounds, endX, endY) || !current()) return rejected(call, "Invalid swipe bounds")
        val path = Path().apply { moveTo(startX.toFloat(), startY.toFloat()); lineTo(endX.toFloat(), endY.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        val completed = CountDownLatch(1)
        val succeeded = java.util.concurrent.atomic.AtomicBoolean()
        val submitted = android.os.Handler(mainLooper).post {
            if (!current()) { completed.countDown(); return@post }
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { succeeded.set(true); completed.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { completed.countDown() }
            }, null)
            if (!accepted) completed.countDown()
        }
        if (!submitted) return failed(call, "Gesture queue unavailable")
        val finished = try { completed.await(1_500L, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        return if (finished && succeeded.get() && current()) success(call, "Swipe completed") else failed(call, "Swipe did not complete")
    }
    private fun openApp(call: DeviceActionCall, targetPackage: String): DeviceActionResult {
        if (call.arguments["package_name"] != targetPackage) return rejected(call, "Target package mismatch")
        val intent = packageManager.getLaunchIntentForPackage(targetPackage) ?: return failed(call, "Target app unavailable")
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return success(call, "Target app opened")
    }

    private fun global(call: DeviceActionCall, action: Int): DeviceActionResult =
        if (performGlobalAction(action)) success(call, "Navigation completed") else failed(call, "Navigation was not accepted")

    private data class CollectedElements(val elements: List<DeviceElement>, val incomplete: Boolean)

    private data class PendingNode(val node: AccessibilityNodeInfo, val ref: String, val protectedAncestor: Boolean)
    private class CollectionState(val bounds: Rect) {
        val elements = ArrayList<DeviceElement>()
        val pending = ArrayDeque<PendingNode>()
        var visited = 0
        var textUnits = 0
        var incomplete = false
    }

    private fun collectElements(root: AccessibilityNodeInfo, window: AccessibilityWindowInfo, current: () -> Boolean): CollectedElements {
        val state = CollectionState(windowBounds(window))
        state.pending.add(PendingNode(AccessibilityNodeInfo.obtain(root), "0", false))
        try {
            while (state.pending.isNotEmpty() && state.elements.size < DeviceAgentLimits.MAX_NODES) {
                check(current()) { "Device task expired" }
                if (++state.visited > DeviceAgentLimits.MAX_NODE_VISITS) { state.incomplete = true; break }
                val pending = state.pending.removeFirst()
                try { collectNode(pending, state) } finally { pending.node.recycle() }
            }
            if (state.pending.isNotEmpty()) state.incomplete = true
        } finally {
            state.pending.forEach { it.node.recycle() }
            state.pending.clear()
        }
        // Container text may aggregate editor descendants. Never expose those aggregates.
        val protectedRefs = state.elements.filter { it.editable || it.sensitive || it.password }.map { it.ref }
        val elements = state.elements.map { element ->
            if (protectedRefs.any { it.startsWith(element.ref + ".") }) element.copy(label = null, text = null) else element
        }
        return CollectedElements(elements, state.incomplete)
    }

    private fun collectNode(item: PendingNode, state: CollectionState) {
        val node = item.node
        val bounds = Rect().also(node::getBoundsInScreen)
        val protected = item.protectedAncestor || sensitive(node)
        val editor = node.isEditable
        if (node.isVisibleToUser && bounds.intersect(state.bounds) && !bounds.isEmpty && bounds.left >= 0 && bounds.top >= 0) {
            val rawLabel = if (protected || item.protectedAncestor) null else node.contentDescription
            // Only leaf text is public. Editable values and descendants are never read.
            val rawText = if (protected || editor || item.protectedAncestor || node.childCount > 0) null else node.text
            val label = safeText(rawLabel)
            val text = safeText(rawText)
            if (rawLabel != null && rawLabel.isNotEmpty() && label == null || rawText != null && rawText.isNotEmpty() && text == null) {
                state.incomplete = true
            }
            state.textUnits += (label?.length ?: 0) + (text?.length ?: 0)
            if (state.textUnits > DeviceAgentLimits.MAX_SNAPSHOT_TEXT_CHARS) { state.incomplete = true; return }
            if (label != null || text != null || node.isClickable || node.isLongClickable || node.isScrollable || editor || protected) {
                state.elements += DeviceElement(
                    item.ref, node.className?.toString()?.substringAfterLast('.')?.take(128) ?: "View", label, text,
                    DeviceBounds(bounds.left, bounds.top, bounds.right, bounds.bottom), node.isClickable,
                    node.isLongClickable, node.isScrollable, editor, node.isPassword, protected, node.isEnabled,
                    if (protected) null else imeActionLabel(node),
                )
            }
        }
        if (item.ref.count { it == '.' } >= DeviceAgentLimits.MAX_DEPTH) {
            if (node.childCount > 0) state.incomplete = true
            return
        }
        if (node.childCount > 32) state.incomplete = true
        for (index in 0 until minOf(node.childCount, 32)) {
            val child = child(node, index) ?: continue
            if (state.pending.size >= DeviceAgentLimits.MAX_PENDING_NODES) { child.recycle(); state.incomplete = true; break }
            state.pending.add(PendingNode(child, "${item.ref}.$index", protected || editor))
        }
    }
    /**
     * Capture is bounded and runs off the service main thread. A separate
     * executor prevents the broker worker from waiting on its own callback.
     */
    @RequiresApi(30)
    private fun captureScreenshot(
        current: () -> Boolean,
        cropBounds: Rect,
        redactions: List<Rect>,
    ): ByteArray? {
        if (!current() || !isDeviceUnlocked()) return null
        val resultLock = Any()
        var result: ByteArray? = null
        var expired = false
        val completed = CountDownLatch(1)
        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                graph.deviceAgentScreenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(value: ScreenshotResult) {
                        try {
                            val encoded = if (current() && isDeviceUnlocked()) {
                                encodeScreenshot(value, cropBounds, redactions)
                            } else {
                                runCatching { value.hardwareBuffer.close() }
                                null
                            }
                            synchronized(resultLock) {
                                if (expired || !current() || !isDeviceUnlocked()) encoded?.fill(0)
                                else result = encoded
                            }
                        } finally {
                            completed.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        completed.countDown()
                    }
                },
            )
        }.onFailure { completed.countDown() }
        val callbackFinished = try {
            completed.await(1_500L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val bytes = synchronized(resultLock) {
            if (!callbackFinished) {
                expired = true
                result?.fill(0)
                result = null
                null
            } else result.also { result = null }
        } ?: return null
        if (current() && isDeviceUnlocked()) return bytes
        bytes.fill(0)
        return null
    }

    @RequiresApi(30)
    private fun encodeScreenshot(value: ScreenshotResult, cropBounds: Rect, redactions: List<Rect>): ByteArray? {
        val buffer: HardwareBuffer = value.hardwareBuffer
        var bitmap: Bitmap? = null
        var cropped: Bitmap? = null
        var redacted: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            bitmap = Bitmap.wrapHardwareBuffer(buffer, value.colorSpace) ?: return null
            val bounds = Rect(cropBounds)
            if (!bounds.intersect(0, 0, bitmap.width, bitmap.height) || bounds.isEmpty) return null
            if (bounds.left != 0 || bounds.top != 0 || bounds.right != bitmap.width || bounds.bottom != bitmap.height) {
                cropped = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
            }
            val base = cropped ?: bitmap
            val sourceBase = if (redactions.isEmpty()) base else {
                base.copy(Bitmap.Config.ARGB_8888, true).also { copy ->
                    redacted = copy
                    val canvas = Canvas(copy)
                    val paint = android.graphics.Paint().apply { color = 0xff000000.toInt() }
                    redactions.forEach { original ->
                        val safe = Rect(original).apply { offset(-bounds.left, -bounds.top) }
                        if (safe.intersect(0, 0, copy.width, copy.height)) canvas.drawRect(safe, paint)
                    }
                }
            }
            val pixels = sourceBase.width.toLong() * sourceBase.height.toLong()
            val source = (if (pixels > dev.zeroinput.ai.api.DeviceAgentLimits.MAX_SCREENSHOT_PIXELS) {
                val scale = kotlin.math.sqrt(
                    dev.zeroinput.ai.api.DeviceAgentLimits.MAX_SCREENSHOT_PIXELS.toDouble() / pixels,
                )
                scaled = sourceBase.scale(
                    (sourceBase.width * scale).toInt().coerceAtLeast(1),
                    (sourceBase.height * scale).toInt().coerceAtLeast(1),
                    filter = true,
                )
                scaled
            } else sourceBase) ?: return null
            val jpeg = compressScreenshot(source, 72) ?: return null
            if (jpeg.size <= dev.zeroinput.ai.api.DeviceAgentLimits.MAX_SCREENSHOT_BYTES) jpeg
            else null.also { jpeg.fill(0) }
        } finally {
            runCatching { buffer.close() }
            listOfNotNull(scaled, redacted, cropped, bitmap).distinct().forEach { image ->
                runCatching { image.recycle() }
            }
        }
    }

    private fun compressScreenshot(bitmap: Bitmap, quality: Int): ByteArray? {
        val output = WipingByteArrayOutputStream()
        return runCatching {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) null else output.toByteArray()
        }.getOrNull().also { output.wipe() }
    }

    private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private fun resolve(root: AccessibilityNodeInfo, ref: String): AccessibilityNodeInfo? {
        val indices = ref.split('.').drop(1).mapNotNull(String::toIntOrNull)
        var current = AccessibilityNodeInfo.obtain(root)
        for (index in indices) {
            val next = child(current, index) ?: run { current.recycle(); return null }
            current.recycle()
            current = next
        }
        return current
    }

    private fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= 33) node.getChild(index, 0) else node.getChild(index)

    private fun focusedWindow(): AccessibilityWindowInfo? {
        if (!isDeviceUnlocked()) return null
        val all = windows
        if (all.size > 32) {
            all.forEach { it.recycle() }
            return null
        }
        val focused = all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
        if (focused.size != 1) {
            all.forEach { it.recycle() }
            return null
        }
        val selected = focused.single()
        all.filterNot { it === selected }.forEach { it.recycle() }
        return selected
    }

    private fun rootOf(window: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= 33) window.getRoot(0) else window.root

    private fun metadataWindow(): DeviceWindowIdentity? {
        val focused = focusedWindow() ?: return null
        return try {
            synchronized(windowPackages) { windowPackages[focused.id] }?.let { DeviceWindowIdentity(it, focused.id) }
        } finally { focused.recycle() }
    }

    private fun occludedRegions(target: AccessibilityWindowInfo): List<Rect> {
        val all = windows
        return try {
            check(all.size <= 32) { "Window limit exceeded" }
            all.filter { it.id != target.id && it.layer > target.layer }.map(::windowBounds)
        } finally { all.forEach { it.recycle() } }
    }

    private fun imeActionLabel(node: AccessibilityNodeInfo): String? = if (Build.VERSION.SDK_INT >= 30 && node.isEditable) {
        safeText(node.actionList.firstOrNull { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }?.label)
    } else null

    private fun matches(node: AccessibilityNodeInfo, element: DeviceElement): Boolean {
        val actual = Rect().also(node::getBoundsInScreen)
        return node.isVisibleToUser && node.isEnabled && !sensitive(node) &&
            actual.left == element.bounds.left && actual.top == element.bounds.top &&
            actual.right == element.bounds.right && actual.bottom == element.bounds.bottom &&
            node.className?.toString()?.substringAfterLast('.') == element.role &&
            node.isEditable == element.editable && node.isClickable == element.clickable &&
            node.isLongClickable == element.longClickable && node.isScrollable == element.scrollable &&
            safeText(node.contentDescription) == element.label &&
            (node.isEditable || node.childCount > 0 || safeText(node.text) == element.text)
    }

    private fun isDeviceUnlocked(): Boolean = getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false

    private fun windowPackage(window: AccessibilityWindowInfo): String? {
        val root = rootOf(window) ?: return null
        return try { root.packageName?.toString()?.takeIf { it.isNotBlank() } } finally { root.recycle() }
    }

    private fun windowBounds(window: AccessibilityWindowInfo): Rect = Rect().also(window::getBoundsInScreen)

    private fun sensitive(node: AccessibilityNodeInfo): Boolean {
        val contentLabel = buildString {
            node.contentDescription?.let(::append)
            node.hintText?.let {
                if (isNotEmpty()) append(' ')
                append(it)
            }
        }
        if (sensitiveLabel(contentLabel)) return true
        if (node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)) return true
        if (node.inputType == 0) return node.isEditable
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            )
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> node.isEditable
        }
    }

    private fun sensitiveLabel(value: CharSequence?): Boolean {
        return DeviceAgentSafety.sensitiveLabel(value)
    }

    private fun safeText(value: CharSequence?): String? = value?.toString()?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= dev.zeroinput.ai.api.DeviceAgentLimits.MAX_ELEMENT_TEXT &&
            it.none { character -> character.isISOControl() && character !in "\n\r\t" }
    }

    private fun success(call: DeviceActionCall, summary: String) =
        DeviceActionResult(call.callId, DeviceActionResultStatus.SUCCESS, summary)

    private fun failed(call: DeviceActionCall, summary: String) =
        DeviceActionResult(call.callId, DeviceActionResultStatus.FAILED, summary, true)

    private fun rejected(call: DeviceActionCall, summary: String) =
        DeviceActionResult(call.callId, DeviceActionResultStatus.REJECTED, summary)

    private fun expired(call: DeviceActionCall) =
        DeviceActionResult(call.callId, DeviceActionResultStatus.EXPIRED, "Device task expired")

    private companion object {
        val CONTENT_EVENTS = setOf(
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        )
    }
}
