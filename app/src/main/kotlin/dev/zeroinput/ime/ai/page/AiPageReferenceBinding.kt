package dev.zeroinput.ime.ai.page

import android.os.Handler
import dev.zeroinput.ai.api.AiReference
import dev.zeroinput.ime.ai.AiWorkbenchController
import dev.zeroinput.ime.ui.ZeroInputView
import java.lang.ref.WeakReference

/** IME-owned review state. Neither the broker nor the service retains captured text. */
internal class AiPageReferenceBinding(
    private val broker: PageReferenceBroker,
    private val enabled: () -> Boolean,
    private val handler: Handler,
    private val workbench: AiWorkbenchController,
    private val allowed: () -> Boolean,
    private val sourcePackage: () -> String?,
    private val view: () -> ZeroInputView?,
    private val openSettings: () -> Unit,
    private val rejected: () -> Unit,
) : AutoCloseable {
    private var snapshot: PageTextSnapshot? = null
    private var reviewToken: Long? = null
    @Volatile private var referenceToken: Long? = null
    private var contextRevision = -1L
    private var pending = false
    private var operation = 0L
    private var expires: Runnable? = null
    private val observer = broker.observeInvalidation {
        handler.post {
            if (reviewToken?.let { !broker.isCurrent(it) } == true ||
                referenceToken?.let { !broker.isCurrent(it) } == true) clearState()
        }
    }

    fun request() {
        if (!allowed()) return
        if (!enabled() || !broker.connected) {
            openSettings()
            return
        }
        val source = sourcePackage()?.takeIf(String::isNotBlank) ?: return
        workbench.stop()
        invalidate()
        contextRevision = workbench.contextState.revision
        val ticket = ++operation
        pending = true
        view()?.renderAiPage(null, loading = true)
        val captureTimeout = Runnable {
            if (ticket == operation && pending) {
                cancelReview()
                view()?.renderAiPage(null)
            }
        }.also { expires = it }
        handler.postDelayed(captureTimeout, PageReferenceBroker.CAPTURE_TIMEOUT_MS)
        val owner = WeakReference(this)
        reviewToken = broker.capture(source) { token, value ->
            owner.get()?.deliver(ticket, token, value)
        }
    }

    private fun deliver(ticket: Long, token: Long, value: PageTextSnapshot?) {
        if (ticket != operation) return
        if (!allowed() || contextRevision != workbench.contextState.revision || !broker.isCurrent(token)) {
            cancelReview()
            view()?.clearAiPage()
            return
        }
        expires?.let(handler::removeCallbacks)
        pending = false
        reviewToken = token
        snapshot = value
        view()?.renderAiPage(value?.texts, value?.incomplete == true)
        val timeout = Runnable {
            if (ticket == operation) { cancelReview(); view()?.clearAiPage() }
        }.also { expires = it }
        handler.postDelayed(timeout, PageReferenceBroker.REVIEW_LIFETIME_MS)
    }

    fun apply(indices: List<Int>) {
        val token = reviewToken ?: return
        val value = snapshot ?: return
        if (!allowed() || !broker.isCurrent(token) ||
            contextRevision != workbench.contextState.revision) { invalidate(); rejected(); return }
        if (indices.isEmpty() || indices.size > AiReference.MAX_REFERENCES ||
            indices.distinct().size != indices.size || indices.any { it !in value.texts.indices }) {
            rejected(); return
        }
        val references = indices.sorted().map { AiReference(value.texts[it]) }
        if (!workbench.addReferences(contextRevision, references)) { rejected(); return }
        referenceToken = token
        releaseReview()
        view()?.clearAiPage()
    }

    /** Submit rechecks synchronously, including before queued invalidation UI callbacks run. */
    fun ensureCurrent(): Boolean {
        val token = referenceToken ?: return true
        if (broker.isCurrent(token)) return true
        invalidate()
        rejected()
        return false
    }

    fun referencesCurrent(): Boolean = referenceToken?.let(broker::isCurrent) != false

    fun cancelReview() {
        val hadReview = pending || snapshot != null
        releaseReview()
        if (hadReview && referenceToken == null) broker.invalidate()
    }

    fun invalidate() { clearState(); broker.invalidate() }

    private fun clearState() {
        releaseReview()
        workbench.clearPageReferences()
        referenceToken = null
        view()?.clearAiPage()
    }

    private fun releaseReview() {
        operation++
        expires?.let(handler::removeCallbacks)
        expires = null
        pending = false
        snapshot = null
        reviewToken = null
    }

    override fun close() { observer.close(); invalidate() }
}
