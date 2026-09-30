package dev.zeroinput.ime.keyboard

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.drawable.toDrawable
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import dev.zeroinput.ime.settings.SettingsRepository
import dev.zeroinput.ime.ui.KeyboardLayoutHost
import dev.zeroinput.ime.ui.KeyboardPlacementMode
import dev.zeroinput.ime.ui.ZeroInputView

/** Adapts IME insets only; never creates an application overlay window. */
internal class KeyboardWindowLayout(
    private val service: InputMethodService,
    private val settings: SettingsRepository,
    private val onInteraction: () -> Unit,
) : AutoCloseable {
    private var host: KeyboardLayoutHost? = null
    private var lastBounds = Rect()
    private var lastFloating = false
    private val landscape: Boolean get() = service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun wrap(keyboard: ZeroInputView): KeyboardLayoutHost {
        host?.release()
        return KeyboardLayoutHost(keyboard.context, keyboard).also { view ->
            host = view
            view.onInteraction = onInteraction
            view.onPlacementChanged = { settings.saveKeyboardPlacement(landscape, it) }
            view.onBoundsChanged = ::boundsChanged
            view.applyPlacement(settings.keyboardPlacement(landscape))
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { configureFrame(); v.requestLayout() }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    fun refresh() {
        val view = host ?: return
        val stored = settings.keyboardPlacement(landscape)
        if (view.placement != stored) view.applyPlacement(stored)
    }

    private fun configureFrame() {
        val view = host ?: return
        val floating = view.placement.mode == KeyboardPlacementMode.FLOATING
        val height = if (floating) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        for (target in listOfNotNull(view, view.parent as? View)) {
            val params = target.layoutParams ?: continue
            if (params.height != height) { params.height = height; target.layoutParams = params }
        }
        service.window?.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
    }

    private fun boundsChanged() {
        configureFrame()
        val view = host ?: return
        val bounds = view.inputBounds()
        val floating = view.placement.mode == KeyboardPlacementMode.FLOATING
        if (bounds != lastBounds || floating != lastFloating) {
            lastBounds = bounds
            lastFloating = floating
            service.window?.window?.decorView?.requestLayout()
        }
    }

    fun computeInsets(outInsets: InputMethodService.Insets) {
        val view = host ?: return
        if (!view.isShown || view.width == 0) return
        val rect = view.inputBounds()
        val location = IntArray(2)
        view.getLocationInWindow(location)
        rect.offset(location[0], location[1])
        val floating = view.placement.mode == KeyboardPlacementMode.FLOATING
        val bottom = service.window?.window?.decorView?.height ?: rect.bottom
        outInsets.contentTopInsets = if (floating) bottom else rect.top
        outInsets.visibleTopInsets = if (floating) bottom else rect.top
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(rect)
    }

    override fun close() { host?.release(); host = null }
}
