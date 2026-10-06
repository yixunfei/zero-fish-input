package dev.zeroinput.ime.clipboard

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.SecureClipboardVault

internal class ClipboardImportView(context: Context) : LinearLayout(context) {
    var onAction: () -> Unit = {}
    var onAiAction: () -> Unit = {}
    var onCancel: () -> Unit = {}

    private val status = TextView(context).apply { textSize = 16f }
    private val content = TextView(context).apply {
        textSize = 16f
        isSaveEnabled = false
        setTextIsSelectable(false)
        visibility = GONE
    }
    private val labelInput = TextInputEditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        filters = arrayOf(InputFilter.LengthFilter(SecureClipboardVault.MAX_LABEL_LENGTH))
        setSingleLine()
        typeface = Typeface.DEFAULT
        isSaveEnabled = false
        filterTouchesWhenObscured = true
    }
    private val labelField = TextInputLayout(context).apply {
        setHint(R.string.secure_item_label)
        addView(labelInput)
        visibility = GONE
    }
    private val action = MaterialButton(context).apply {
        isAllCaps = false
        letterSpacing = 0f
        minHeight = dp(48)
        filterTouchesWhenObscured = true
        setOnClickListener { onAction() }
    }
    private val aiAction = MaterialButton(context).apply {
        isAllCaps = false
        letterSpacing = 0f
        minHeight = dp(48)
        filterTouchesWhenObscured = true
        setOnClickListener { onAiAction() }
        visibility = GONE
    }

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        addView(MaterialToolbar(context).apply {
            title = context.getString(R.string.copy_to_zeroinput)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = context.getString(R.string.cancel)
            filterTouchesWhenObscured = true
            setNavigationOnClickListener { onCancel() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                addView(status, row())
                addView(labelField, row())
                addView(content, row())
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(action, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(20), dp(8), dp(20), dp(16))
        })
        addView(aiAction, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(20), 0, dp(20), dp(16))
        })
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    fun showReview(length: Int, enabled: Boolean) {
        status.text = resources.getQuantityString(R.plurals.clipboard_import_received, length, length)
        action.setText(if (enabled) R.string.clipboard_import_authenticate else R.string.enable_secure_clipboard)
        aiAction.setText(R.string.clipboard_import_ai)
        aiAction.visibility = VISIBLE
        aiAction.isEnabled = true
    }

    fun showAuthorized(value: String) {
        status.setText(R.string.clipboard_import_confirm)
        content.text = value
        content.visibility = VISIBLE
        labelField.visibility = VISIBLE
        action.setText(R.string.save)
        action.isEnabled = true
        aiAction.visibility = GONE
    }

    fun label(): String = labelInput.text?.toString().orEmpty()

    fun showBusy(authenticating: Boolean) {
        clearText()
        action.isEnabled = false
        aiAction.visibility = GONE
        status.setText(if (authenticating) R.string.authenticating else R.string.clipboard_import_saving)
    }

    fun clearText() {
        content.text = ""
        content.visibility = GONE
        labelInput.text?.clear()
        labelField.visibility = GONE
        aiAction.visibility = GONE
    }

    private fun row() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(16)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
