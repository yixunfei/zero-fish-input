package dev.zeroinput.ime.management

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ime.R

/** Content-free feedback; retry callbacks must reload a list, never repeat a mutation. */
internal class ManagementStatusView(context: Context) : LinearLayout(context) {
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
    }
    private val message = TextView(context).apply {
        id = R.id.management_status_message
        textSize = 14f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val retry = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        id = R.id.management_status_retry
        setText(R.string.management_reload)
        isAllCaps = false
        minHeight = dp(48)
        filterTouchesWhenObscured = true
    }

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        setPadding(dp(16), dp(4), dp(16), dp(4))
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(4)))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(retry, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        clear()
    }

    fun loading() = render(context.getString(R.string.management_loading), true, null)

    fun show(text: CharSequence) = render(text, false, null)

    fun failure(resource: Int, reload: () -> Unit) = render(context.getString(resource), false, reload)

    fun clear() = render("", false, null)

    private fun render(text: CharSequence, loading: Boolean, reload: (() -> Unit)?) {
        visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        message.text = text
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        retry.visibility = if (reload == null) View.GONE else View.VISIBLE
        retry.setOnClickListener(if (reload == null) null else OnClickListener { reload() })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
