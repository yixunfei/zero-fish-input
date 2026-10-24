package dev.zeroinput.ime.settings

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.UserTerm

internal data class UserPhraseEdit(val shortcut: String, val value: String, val language: InputLanguage, val weight: Int)

/** UI owns editable text; workers receive an immutable value only after validation. */
internal object UserPhraseEditor {
    fun field(context: Context, hint: Int, maxLength: Int, lines: Int = 1) = EditText(context).apply {
        setHint(hint)
        if (lines == 1) setSingleLine(true) else maxLines = lines
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
        isSaveEnabled = false
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
    }

    fun show(context: Context, term: UserTerm?, busy: () -> Boolean,
        save: (UserPhraseEdit, AlertDialog) -> Unit): AlertDialog {
        val code = field(context, R.string.user_dictionary_shortcut, 64).apply { setText(term?.shortcut.orEmpty()) }
        val phrase = field(context, R.string.user_dictionary_value, 128, 2).apply { setText(term?.value.orEmpty()) }
        val weight = field(context, R.string.user_dictionary_frequency, 7).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(String.format(java.util.Locale.ROOT, "%d", term?.frequency ?: 1))
        }
        val english = View.generateViewId()
        val chinese = View.generateViewId()
        val languages = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(context).apply { id = chinese; setText(R.string.language_chinese) })
            addView(RadioButton(context).apply { id = english; setText(R.string.language_english) })
            check(if (term?.language == InputLanguage.ENGLISH) english else chinese)
        }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            addLabeledField(code); addLabeledField(phrase); addLabeledField(weight)
            addView(languages)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (term == null) R.string.user_dictionary_add else R.string.user_dictionary_edit)
            .setView(ScrollView(context).apply { addView(form) })
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { code.text.clear(); phrase.text.clear(); weight.text.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (busy()) return@setOnClickListener
                val count = weight.text.toString().toIntOrNull()
                if (code.text.isBlank() || phrase.text.isBlank()) {
                    code.error = context.getString(R.string.user_dictionary_required)
                } else if (count == null || count !in 1..1_000_000) {
                    weight.error = context.getString(R.string.user_dictionary_weight_invalid)
                } else {
                    save(UserPhraseEdit(code.text.toString(), phrase.text.toString(),
                        if (languages.checkedRadioButtonId == english) InputLanguage.ENGLISH else InputLanguage.CHINESE, count), dialog)
                }
            }
        }
        dialog.show()
        return dialog
    }

    private fun LinearLayout.addLabeledField(field: EditText) {
        field.id = View.generateViewId()
        addView(TextView(context).apply {
            text = field.hint
            labelFor = field.id
        })
        addView(field)
    }
}
