package dev.zeroinput.ime.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton

enum class PrivateDraftAction { SAVE_AND_CLOSE, SAVE_AND_SUBMIT, SUBMIT_ONLY }

internal class PrivateDraftPanelView(context: Context) : ScrollView(context) {
    var onPreview: () -> Unit = {}
    var onFinish: (PrivateDraftAction) -> Unit = {}
    var onCaretRequested: (KeyboardAction.MoveDraftCaret) -> Unit = {}
    private var caret: DraftCaretUi? = null
    fun renderCaret(value: DraftCaretUi) { caret = value }

    private val draft = DraftTextView(context).apply {
        isClickable = true
        onCaretRequested = { this@PrivateDraftPanelView.onCaretRequested(it) }
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        isSaveEnabled = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val draftScroll = ScrollView(context).apply {
        isSaveEnabled = false
        addView(draft, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val scrollToDraftCaret = Runnable {
        val offset = caret?.offset ?: draft.text.length
        if (offset in 0..draft.text.length) draft.bringPointIntoView(offset)
    }
    private var fullVisible = false
    private val preview = command(R.string.private_draft_preview) { onPreview() }
    private val save = command(R.string.private_draft_save_close) { onFinish(PrivateDraftAction.SAVE_AND_CLOSE) }
    private val submit = command(R.string.private_draft_save_submit) { onFinish(PrivateDraftAction.SAVE_AND_SUBMIT) }
    private val submitOnly = command(R.string.private_draft_submit_only) { onFinish(PrivateDraftAction.SUBMIT_ONLY) }

    init {
        isSaveFromParentEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                addView(draftScroll, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(preview, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
            })
            addView(LinearLayout(context).apply {
                addView(save, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(submit, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(submitOnly, LinearLayout.LayoutParams(0, dp(48), 1f))
            })
        })
        render("", hasInput = false, busy = false, showFull = false)
    }

    fun render(value: String, hasInput: Boolean, busy: Boolean, showFull: Boolean) {
        preview.setText(if (showFull) R.string.private_draft_mask else R.string.private_draft_preview)
        preview.isEnabled = !busy
        save.isEnabled = !busy && hasInput
        submit.isEnabled = save.isEnabled
        submitOnly.isEnabled = save.isEnabled
        val showBody = showFull && !busy
        val label = when {
            busy -> context.getString(R.string.private_draft_saving)
            value.isEmpty() -> context.getString(R.string.private_draft_hint)
            else -> value
        }
        if (showBody != fullVisible || draft.text.toString() != label) {
            fullVisible = showBody
            draft.setSingleLine(!showBody)
            draft.ellipsize = if (showBody) null else TextUtils.TruncateAt.START
            draftScroll.isVerticalScrollBarEnabled = showBody
            draft.text = label
        }
        draft.renderCaret(if (busy || !hasInput) null else caret)
        draftScroll.removeCallbacks(scrollToDraftCaret)
        if (showBody) draftScroll.post(scrollToDraftCaret) else draftScroll.scrollTo(0, 0)
    }

    override fun onDetachedFromWindow() {
        caret = null
        render("", hasInput = false, busy = false, showFull = false)
        super.onDetachedFromWindow()
    }

    private fun command(label: Int, action: () -> Unit) =
        MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setText(label)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            maxLines = 2
            filterTouchesWhenObscured = true
            setOnClickListener { action() }
        }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
