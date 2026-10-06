package dev.zeroinput.ime.settings

import android.graphics.Bitmap
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardBackground
import dev.zeroinput.ime.ui.ZeroInputView

/** View-scoped ownership: cancelling a binding prevents late image delivery to a replacement view. */
internal class KeyboardAppearanceBinding(
    private val load: (KeyboardAppearance, (Bitmap?) -> Unit) -> AutoCloseable,
) : AutoCloseable {
    constructor(store: KeyboardBackgroundStore) : this(store::load)
    private var request: AutoCloseable? = null
    private var bitmap: Bitmap? = null
    private var revision = 0L
    private var target: ZeroInputView? = null
    private var loaded: KeyboardAppearance? = null
    private var completion: (Boolean) -> Unit = {}

    fun apply(view: ZeroInputView, appearance: KeyboardAppearance, completed: (Boolean) -> Unit = {}) {
        val previous = loaded
        val sameImage = previous?.imageRevision == appearance.imageRevision &&
            previous.background == appearance.background && previous.backgroundBlur == appearance.backgroundBlur &&
            previous.material == appearance.material
        completion = completed
        if (sameImage) {
            if (target !== view) loaded?.let { target?.applyAppearance(it) }
            target = view
            loaded = appearance
            if (bitmap != null || request == null) view.applyAppearance(appearance, bitmap)
            if (request == null) completed(bitmap != null || appearance.background != KeyboardBackground.IMAGE)
            return
        }
        revision++
        request?.close()
        request = null
        if (target !== view) loaded?.let { target?.applyAppearance(it) }
        target = view
        loaded = appearance
        if (appearance.background != KeyboardBackground.IMAGE || appearance.imageRevision.isEmpty()) {
            view.applyAppearance(appearance)
            bitmap?.recycle(); bitmap = null
            completed(true)
            return
        }
        // Keep the previous pixels until the replacement is ready.
        bitmap?.let { view.applyAppearance(appearance, it) }
        val token = revision
        request = load(appearance) { result ->
            val current = target
            if (revision != token || current == null) { result?.recycle(); return@load }
            request = null
            val previousBitmap = bitmap
            bitmap = result
            current.applyAppearance(loaded ?: appearance, result)
            previousBitmap?.recycle()
            completion(result != null)
        }
    }

    override fun close() {
        revision++
        request?.close(); request = null
        // Drop the drawable before recycling its exclusively-owned pixels.
        loaded?.let { target?.applyAppearance(it) }
        target = null
        loaded = null
        completion = {}
        bitmap?.recycle(); bitmap = null
    }
}
