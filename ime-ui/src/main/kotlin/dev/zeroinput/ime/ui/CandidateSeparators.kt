package dev.zeroinput.ime.ui

import android.graphics.Canvas
import android.graphics.Paint
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/** Inset rules distinguish adjacent words without changing their touch bounds. */
internal class CandidateSeparators : RecyclerView.ItemDecoration() {
    var enabled = true
    private val paint = Paint()

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (!enabled) return
        val inset = 8 * parent.resources.displayMetrics.density
        paint.color = MaterialColors.getColor(parent.context,
            com.google.android.material.R.attr.colorOutlineVariant,
            androidx.core.graphics.ColorUtils.setAlphaComponent(MaterialColors.getColor(parent.context,
                com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.GRAY), 36))
        paint.strokeWidth = parent.resources.displayMetrics.density
        for (index in 0 until parent.childCount) {
            val view = parent.getChildAt(index)
            val params = view.layoutParams as? GridLayoutManager.LayoutParams ?: continue
            canvas.drawLine(view.left + inset, view.bottom.toFloat(), view.right - inset, view.bottom.toFloat(), paint)
            if (params.spanIndex > 0) {
                val edge = if (parent.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) view.right else view.left
                canvas.drawLine(edge.toFloat(), view.top + inset, edge.toFloat(), view.bottom - inset, paint)
            }
        }
    }
}
