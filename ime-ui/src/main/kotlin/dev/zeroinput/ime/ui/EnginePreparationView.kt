package dev.zeroinput.ime.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** Persistent status outside candidate/tools panels; progress has no estimated percentage. */
internal class EnginePreparationView(context: Context) : LinearLayout(context) {
    private val notice = EnginePreparationNotice()
    private var toast: Toast? = null
    val preferredHeight: Int get() = dp(32)

    init {
        orientation = VERTICAL
        visibility = GONE
        val attributes = context.obtainStyledAttributes(intArrayOf(
            com.google.android.material.R.attr.colorPrimary,
            com.google.android.material.R.attr.colorOnPrimary,
        ))
        val background = attributes.getColor(0, android.graphics.Color.DKGRAY)
        val foreground = attributes.getColor(1, android.graphics.Color.WHITE)
        attributes.recycle()
        setBackgroundColor(background)
        addView(TextView(context).apply {
            setText(R.string.engine_preparing_basic)
            setTextColor(foreground)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(foreground)
            contentDescription = context.getString(R.string.engine_preparing)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(3)))
    }

    fun render(status: InputEngineStatus) {
        notice.update(status)
        val preparing = status == InputEngineStatus.PREPARING
        visibility = if (preparing) VISIBLE else GONE
        if (!preparing) cancelToast()
    }

    fun onInputAttempt() {
        if (notice.onInputAttempt()) {
            toast = Toast.makeText(context, R.string.engine_preparing_notice, Toast.LENGTH_LONG).also { it.show() }
        }
    }

    fun resetEditor() {
        cancelToast()
        notice.resetEditor()
    }

    override fun onDetachedFromWindow() {
        cancelToast()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != VISIBLE) cancelToast()
        super.onWindowVisibilityChanged(visibility)
    }

    private fun cancelToast() { toast?.cancel(); toast = null }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
