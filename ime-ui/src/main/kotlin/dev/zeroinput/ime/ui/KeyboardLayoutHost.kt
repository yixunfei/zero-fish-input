package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Hosts every input panel inside one movable, bounded IME surface. */
class KeyboardLayoutHost(context: Context, val keyboard: ZeroInputView) : ViewGroup(context) {
    var onPlacementChanged: (KeyboardPlacement) -> Unit = {}
    var onInteraction: () -> Unit = {}
    var onBoundsChanged: () -> Unit = {}
    var placement: KeyboardPlacement = KeyboardPlacement()
        private set
    var minimized: Boolean = false
        private set
    private var gesturePlacement = placement
    private var gestureBounds = Rect()
    private val safeInsets = Rect()
    private val controls = KeyboardPlacementControls(context)
    private var menu: PopupMenu? = null
    private val restore = TextView(context).apply {
        text = "⌨"
        contentDescription = context.getString(R.string.keyboard_restore)
        gravity = Gravity.CENTER
        textSize = 26f
        isClickable = true
        isFocusable = true
        setOnClickListener { setMinimized(false) }
    }
    private val surface = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorSurface, Color.WHITE))
        addView(controls, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(keyboard, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(restore, LinearLayout.LayoutParams(dp(56), dp(56)))
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(surface)
        keyboard.setExternalInsets(true)
        keyboard.onPlacementRequested = ::showPlacementMenu
        keyboard.onPanelExpansionChanged = { requestLayout(); onBoundsChanged() }
        controls.onDock = { selectMode(KeyboardPlacementMode.DOCKED) }
        controls.onFlip = { selectMode(if (placement.mode == KeyboardPlacementMode.LEFT_HAND)
            KeyboardPlacementMode.RIGHT_HAND else KeyboardPlacementMode.LEFT_HAND) }
        controls.onMinimize = { setMinimized(true) }
        controls.onGestureStart = {
            onInteraction(); keyboard.cancelPendingGestures()
            gesturePlacement = placement
            gestureBounds = Rect(surface.left, surface.top, surface.right, surface.bottom)
        }
        controls.onMove = ::move
        controls.onResize = ::resize
        controls.onAccessibleAdjust = { moving, direction ->
            onInteraction()
            keyboard.cancelPendingGestures()
            update(if (moving) placement.copy(verticalPosition = placement.verticalPosition + direction * 0.1f)
                else placement.copy(floatingWidth = placement.floatingWidth + direction * 0.05f,
                    oneHandWidth = placement.oneHandWidth + direction * 0.05f,
                    heightScale = placement.heightScale + direction * 0.05f), true)
        }
        controls.onAccessibleMoveHorizontal = { direction ->
            if (placement.mode == KeyboardPlacementMode.FLOATING) {
                onInteraction()
                keyboard.cancelPendingGestures()
                update(placement.copy(horizontalPosition = placement.horizontalPosition + direction * 0.1f), true)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            safeInsets.set(bars.left, bars.top, bars.right, bars.bottom)
            requestLayout()
            insets
        }
        render()
    }

    fun applyPlacement(value: KeyboardPlacement) { update(value, false) }

    fun selectMode(mode: KeyboardPlacementMode) {
        onInteraction()
        minimized = false
        keyboard.cancelPendingGestures()
        update(placement.copy(mode = mode), true)
    }

    fun setMinimized(value: Boolean) {
        if (value && placement.mode != KeyboardPlacementMode.FLOATING) return
        onInteraction()
        keyboard.cancelPendingGestures()
        minimized = value
        render()
    }

    /** Coordinates relative to this host; only this rectangle accepts IME touches. */
    fun inputBounds(): Rect = Rect(surface.left, surface.top, surface.right, surface.bottom)

    private fun update(value: KeyboardPlacement, persist: Boolean) {
        placement = value.sanitized()
        render()
        if (persist) onPlacementChanged(placement)
    }

    private fun render() {
        controls.visibility = if (!minimized && placement.mode != KeyboardPlacementMode.DOCKED) VISIBLE else GONE
        controls.render(placement.mode)
        keyboard.visibility = if (minimized) GONE else VISIBLE
        restore.visibility = if (minimized) VISIBLE else GONE
        keyboard.setLayoutHeightScale(if (placement.mode == KeyboardPlacementMode.FLOATING) placement.heightScale else 1f)
        requestLayout()
        onBoundsChanged()
    }

    private fun showPlacementMenu(anchor: View) {
        onInteraction()
        menu?.dismiss()
        menu = PopupMenu(context, anchor).apply {
            val labels = listOf(R.string.keyboard_docked, R.string.keyboard_left_hand,
                R.string.keyboard_right_hand, R.string.keyboard_floating)
            KeyboardPlacementMode.entries.forEachIndexed { index, mode ->
                menu.add(0, index, index, labels[index]).isChecked = placement.mode == mode
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item -> selectMode(KeyboardPlacementMode.entries[item.itemId]); true }
            show()
        }
    }

    private fun move(x: Float, y: Float, done: Boolean) {
        if (placement.mode != KeyboardPlacementMode.FLOATING) return
        val freeX = (width - safeInsets.left - safeInsets.right - surface.width).coerceAtLeast(1)
        val freeY = (height - safeInsets.top - safeInsets.bottom - surface.height).coerceAtLeast(1)
        update(gesturePlacement.copy(
            horizontalPosition = (gestureBounds.left - safeInsets.left + x) / freeX,
            verticalPosition = (gestureBounds.top - safeInsets.top + y) / freeY), done)
    }

    private fun resize(x: Float, y: Float, done: Boolean) {
        val available = (width - safeInsets.left - safeInsets.right).coerceAtLeast(1)
        val delta = x / available
        update(gesturePlacement.copy(
            floatingWidth = gesturePlacement.floatingWidth + delta,
            oneHandWidth = gesturePlacement.oneHandWidth +
                if (placement.mode == KeyboardPlacementMode.RIGHT_HAND) -delta else delta,
            heightScale = gesturePlacement.heightScale + y / (gestureBounds.height() - dp(48)).coerceAtLeast(1)), done)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val floating = placement.mode == KeyboardPlacementMode.FLOATING
        val offered = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 }
            ?: dp(resources.configuration.screenHeightDp)
        val top = if (floating || keyboard.isPanelExpanded) safeInsets.top else 0
        val availableWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(0)
        val availableHeight = (offered - top - safeInsets.bottom).coerceAtLeast(0)
        val panelWidth = if (minimized) minOf(dp(56), availableWidth)
            else if (keyboard.isPanelExpanded) availableWidth
            else KeyboardPlacementGeometry.width(placement, availableWidth, dp(240))
        surface.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, if (floating || keyboard.isPanelExpanded) offered
            else minOf(offered, surface.measuredHeight + safeInsets.bottom))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val insetTop = if (placement.mode == KeyboardPlacementMode.FLOATING) safeInsets.top else 0
        val (x, y) = KeyboardPlacementGeometry.position(placement,
            (width - safeInsets.left - safeInsets.right).coerceAtLeast(0),
            (height - insetTop - safeInsets.bottom).coerceAtLeast(0), surface.measuredWidth, surface.measuredHeight)
        surface.layout(x + safeInsets.left, y + insetTop,
            x + safeInsets.left + surface.measuredWidth, y + insetTop + surface.measuredHeight)
        onBoundsChanged()
    }

    override fun onDetachedFromWindow() { menu?.dismiss(); menu = null; super.onDetachedFromWindow() }
    fun release() {
        menu?.dismiss(); menu = null
        onPlacementChanged = {}; onInteraction = {}; onBoundsChanged = {}
        keyboard.onPlacementRequested = {}
        keyboard.onPanelExpansionChanged = {}
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
