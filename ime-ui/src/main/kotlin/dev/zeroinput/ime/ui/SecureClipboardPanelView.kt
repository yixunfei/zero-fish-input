package dev.zeroinput.ime.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

data class SecureClipboardItemUi(
    val id: String,
    val displayName: String,
)

sealed interface ClipboardCopyUi {
    data class Review(val length: Int, val enableOnSave: Boolean) : ClipboardCopyUi
    data object Authenticating : ClipboardCopyUi
    data object ConfirmSave : ClipboardCopyUi
    data object Saving : ClipboardCopyUi
}

class SecureClipboardPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    var onItemSelected: (String) -> Unit = {}
    var onManageRequested: () -> Unit = {}
    var onCopySelectionRequested: () -> Unit = {}
    var onCopyConfirmationAction: () -> Unit = {}
    var onCopyConfirmationCancelled: () -> Unit = {}
    var onPasteConfirmed: () -> Unit = {}
    var onPasteCancelled: () -> Unit = {}
    private var pasteConfirmation = false
    private var copyConfirmation: ClipboardCopyUi? = null
    private var renderedCopyConfirmation: ClipboardCopyUi? = null
    private var enabled = false
    private var failed = false
    private var items = emptyList<SecureClipboardItemUi>()
    private var renderedPasteConfirmation = false
    private val copySelection = MaterialButton(context).apply {
        setText(R.string.secure_clipboard_copy_selection)
        isAllCaps = false
        minHeight = dp(48)
        isEnabled = false
        filterTouchesWhenObscured = true
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setOnClickListener { onCopySelectionRequested() }
    }

    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    init {
        isFillViewport = true
        addView(list)
    }

    fun render(enabled: Boolean, items: List<SecureClipboardItemUi>, failed: Boolean = false) {
        if (list.childCount != 0 && this.enabled == enabled && this.items == items && this.failed == failed &&
            renderedPasteConfirmation == pasteConfirmation && renderedCopyConfirmation == copyConfirmation) return
        renderedPasteConfirmation = pasteConfirmation
        renderedCopyConfirmation = copyConfirmation
        this.enabled = enabled
        this.items = items
        this.failed = failed
        list.removeAllViews()
        copyConfirmation?.let {
            renderCopyReview(it)
            return
        }
        if (pasteConfirmation) {
            addStatus(context.getString(R.string.secure_clipboard_confirm_hint))
            addCommand(R.string.secure_clipboard_confirm_paste) { onPasteConfirmed() }
            addCommand(R.string.secure_clipboard_cancel_paste) { onPasteCancelled() }
            return
        }
        list.addView(copySelection)
        when {
            !enabled -> addStatus(context.getString(R.string.secure_clipboard_disabled))
            failed -> addStatus(context.getString(R.string.secure_clipboard_load_failed))
            items.isEmpty() -> addStatus(context.getString(R.string.secure_clipboard_empty))
            else -> items.forEach(::addItem)
        }
        list.addView(manageButton())
    }

    fun renderCopyAvailable(available: Boolean) { copySelection.isEnabled = available }

    fun renderCopyConfirmation(state: ClipboardCopyUi?) {
        if (copyConfirmation == state) return
        copyConfirmation = state
        scrollTo(0, 0)
        render(enabled, items, failed)
    }

    private fun renderCopyReview(state: ClipboardCopyUi) {
        when (state) {
            is ClipboardCopyUi.Review -> {
                addStatus(resources.getQuantityString(R.plurals.secure_clipboard_copy_review, state.length, state.length))
                val action = if (state.enableOnSave) R.string.secure_clipboard_copy_enable
                    else R.string.secure_clipboard_copy_authenticate
                addCommand(action) { onCopyConfirmationAction() }
            }
            ClipboardCopyUi.Authenticating -> addStatus(context.getString(R.string.secure_clipboard_copy_authenticating))
            ClipboardCopyUi.ConfirmSave -> {
                addStatus(context.getString(R.string.secure_clipboard_copy_save_hint))
                addCommand(R.string.secure_clipboard_copy_save) { onCopyConfirmationAction() }
            }
            ClipboardCopyUi.Saving -> addStatus(context.getString(R.string.secure_clipboard_copy_saving))
        }
        addCommand(R.string.secure_clipboard_copy_cancel) { onCopyConfirmationCancelled() }
    }

    fun renderPasteConfirmation(visible: Boolean) {
        if (pasteConfirmation == visible) return
        pasteConfirmation = visible
        render(enabled, items, failed)
    }

    private fun addCommand(label: Int, action: () -> Unit) {
        list.addView(MaterialButton(context).apply {
            setText(label)
            minHeight = dp(48)
            isAllCaps = false
            filterTouchesWhenObscured = true
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { action() }
        })
    }

    private fun addStatus(value: String) {
        list.addView(TextView(context).apply {
            text = value
            gravity = Gravity.CENTER
            textSize = 15f
            minHeight = dp(72)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        })
    }

    private fun addItem(item: SecureClipboardItemUi) {
        list.addView(MaterialButton(context).apply {
            text = context.getString(R.string.secure_clipboard_item, item.displayName)
            contentDescription = context.getString(R.string.secure_clipboard_item_description, item.displayName)
            setIconResource(R.drawable.ic_lock)
            iconSize = dp(18)
            iconPadding = dp(10)
            textSize = 16f
            letterSpacing = 0f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            cornerRadius = dp(6)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply {
                setMargins(0, dp(3), 0, dp(3))
            }
            setOnClickListener { onItemSelected(item.id) }
        })
    }

    private fun manageButton() = panelIconButton(
        context,
        android.R.drawable.ic_menu_manage,
        R.string.secure_clipboard_manage,
    ) { onManageRequested() }.apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.END
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
