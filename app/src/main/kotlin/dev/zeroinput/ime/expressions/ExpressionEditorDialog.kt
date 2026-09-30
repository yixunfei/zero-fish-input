package dev.zeroinput.ime.expressions

import android.content.Context
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ui.EmojiCatalog
import dev.zeroinput.ime.ui.KaomojiGroup
import dev.zeroinput.userdata.ExpressionLimits
import dev.zeroinput.userdata.PersonalExpression
import java.util.Locale

internal data class ExpressionDraft(val value: String, val name: String, val keywords: String, val group: String)

internal class ExpressionEditorDialog(context: Context, entry: PersonalExpression?, onSave: (ExpressionDraft) -> Unit) {
    private val fields = mutableListOf<TextInputEditText>()
    private val value = field(context, R.string.expression_text, ExpressionLimits.TEXT, entry?.value.orEmpty())
    private val name = field(context, R.string.expression_name, ExpressionLimits.NAME, entry?.name.orEmpty())
    private val keywords = field(context, R.string.expression_keywords, ExpressionLimits.KEYWORDS, entry?.keywords.orEmpty())
    private val group = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            KaomojiGroup.entries.map { context.getString(it.label) })
        setSelection(KaomojiGroup.entries.indexOfFirst { it.name.lowercase(Locale.ROOT) == entry?.group }.coerceAtLeast(0))
        contentDescription = context.getString(R.string.expression_group)
        isSaveEnabled = false
    }
    val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
        .setTitle(if (entry == null) R.string.expression_add_custom else R.string.expression_edit_custom)
        .setView(ScrollView(context).apply {
            isSaveEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val space = (20 * resources.displayMetrics.density).toInt()
                setPadding(space, space / 2, space, 0)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                addView(value)
                addView(name)
                addView(keywords)
                addView(group, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * resources.displayMetrics.density).toInt()))
            })
        })
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.save, null)
        .create()

    init {
        dialog.setOnDismissListener { fields.forEach { it.text?.clear() } }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                filterTouchesWhenObscured = true
                setOnClickListener {
                    val text = value.editText?.text?.toString().orEmpty()
                    val title = name.editText?.text?.toString().orEmpty()
                    val words = keywords.editText?.text?.toString().orEmpty()
                    value.error = when {
                        !EmojiCatalog.isReady -> context.getString(dev.zeroinput.ime.ui.R.string.expression_loading)
                        !ExpressionLimits.validText(text) -> context.getString(R.string.expression_invalid)
                        EmojiCatalog.find(text) != null -> context.getString(R.string.expression_builtin_duplicate)
                        else -> null
                    }
                    name.error = if (ExpressionLimits.validText(title, ExpressionLimits.NAME)) null else context.getString(R.string.expression_required)
                    keywords.error = if (ExpressionLimits.validText(words, ExpressionLimits.KEYWORDS, true)) null else context.getString(R.string.expression_invalid)
                    if (value.error == null && name.error == null && keywords.error == null) {
                        onSave(ExpressionDraft(text, title, words, KaomojiGroup.entries[group.selectedItemPosition].name.lowercase(Locale.ROOT)))
                    }
                }
            }
        }
    }

    fun setBusy(busy: Boolean) { dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy }

    private fun field(context: Context, hint: Int, max: Int, initial: String) = TextInputLayout(context).apply {
        setHint(hint)
        isCounterEnabled = true
        counterMaxLength = max
        addView(TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(max))
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            filterTouchesWhenObscured = true
            setText(initial)
            fields += this
        })
    }
}
