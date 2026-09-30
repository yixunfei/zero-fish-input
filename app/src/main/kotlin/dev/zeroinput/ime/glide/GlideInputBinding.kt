package dev.zeroinput.ime.glide

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.zeroinput.engine.api.GlideCandidate
import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.ime.core.InputCommand
import dev.zeroinput.ime.core.InputSessionController
import dev.zeroinput.ime.ui.GlideLetterCase
import dev.zeroinput.ime.ui.ZeroInputView
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Binds public decoding to the current view and revalidates every engine replay slice. */
internal class GlideInputBinding(
    context: Context,
    private val handler: Handler,
    private val identity: () -> GlideSessionIdentity?,
    private val controller: () -> InputSessionController?,
    private val beforeAction: () -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private var view: ZeroInputView? = null
    private val revision = AtomicLong()
    private var replay: GlideCodeReplay? = null
    private val coordinator = GlideCoordinator(
        createDecoder = { BundledGlideDecoder.create(appContext) },
        currentIdentity = identity,
        post = { handler.post(it) },
        remove = { handler.removeCallbacks(it) },
        deliver = { candidates, failed -> view?.renderGlideCandidates(candidates, failed = failed) },
    )

    fun bind(target: ZeroInputView) {
        invalidate()
        view = target
        target.onGlideStarted = beforeAction
        target.onGlideRequested = { request, letterCase ->
            beforeAction()
            if (view === target && identity()?.layout == request.layout) {
                target.renderGlideCandidates(emptyList(), busy = true)
                coordinator.request(request, letterCase)
            }
        }
        target.onGlideCandidateSelected = ::select
    }

    private fun select(candidate: GlideCandidate) {
        val selection = coordinator.consume(candidate) ?: return
        beforeAction()
        val binding = identity() ?: return
        val target = controller() ?: return
        val ticket = revision.get()
        val code = when {
            binding.layout != GlideLayout.ENGLISH_QWERTY -> candidate.inputCode
            selection.letterCase == GlideLetterCase.UPPER -> candidate.inputCode.uppercase(Locale.ROOT)
            selection.letterCase == GlideLetterCase.INITIAL_CAPITAL -> candidate.inputCode.replaceFirstChar(Char::uppercaseChar)
            else -> candidate.inputCode
        }
        val operation = GlideCodeReplay(code, binding.layout == GlideLayout.ENGLISH_QWERTY,
            isCurrent = { revision.get() == ticket && identity() == binding && controller() === target },
            send = target::handle, post = { handler.post(it) }, remove = { handler.removeCallbacks(it) })
        replay = operation
        // Chinese readings append through normal engine semantics, including partial segment selection.
        operation.start(flushEnglishComposition = target.state.snapshot.isComposing)
    }

    fun invalidate() {
        val ticket = revision.incrementAndGet()
        coordinator.revoke()
        if (Looper.myLooper() == handler.looper) clearPending()
        else handler.post { if (revision.get() == ticket) clearPending() }
    }

    private fun clearPending() {
        coordinator.invalidate()
        replay?.cancel(); replay = null
        view?.clearGlideCandidates()
    }

    override fun close() { invalidate(); coordinator.close(); view = null }
}
