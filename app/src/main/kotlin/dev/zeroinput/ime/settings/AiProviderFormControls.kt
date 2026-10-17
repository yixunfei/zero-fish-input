package dev.zeroinput.ime.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.zeroinput.ime.R

/** Provider-only fields keep labels, errors and choice indicators inside Material outlines. */
@SuppressLint("ViewConstructor")
internal class AiProviderTextField(
    context: Context, label: Int, value: String, max: Int, type: Int,
) : TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle) {
    val input = TextInputEditText(this.context).apply {
        inputType = type
        typeface = Typeface.DEFAULT
        setSingleLine(type and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0)
        filters = arrayOf(InputFilter.LengthFilter(max))
        isSaveEnabled = false
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setText(value)
    }
    val value: String get() = input.text.toString().trim()

    init {
        boxBackgroundMode = BOX_BACKGROUND_OUTLINE
        setHint(label)
        isSaveEnabled = false
        layoutParams = formLayout(context)
        addView(input)
    }
}

@SuppressLint("ViewConstructor")
internal class AiProviderChoiceField(
    context: Context, label: Int, description: Int, private val chosen: (Int) -> Unit,
) : TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle) {
    val input = MaterialAutoCompleteTextView(this.context).apply {
        inputType = InputType.TYPE_NULL
        keyListener = null
        isSaveEnabled = false
        contentDescription = context.getString(description)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = (48 * resources.displayMetrics.density).toInt()
        setSingleLine(false)
        setHorizontallyScrolling(false)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setOnItemClickListener { _, _, position, _ -> chosen(position) }
    }

    init {
        boxBackgroundMode = BOX_BACKGROUND_OUTLINE
        setHint(label)
        isSaveEnabled = false
        layoutParams = formLayout(context)
        addView(input)
        endIconMode = END_ICON_DROPDOWN_MENU
    }

    fun render(entries: List<String>, selected: String) {
        input.dismissDropDown()
        input.setAdapter(ArrayAdapter(context, R.layout.ai_provider_choice_item, entries))
        input.setText(selected, false)
        isEnabled = entries.isNotEmpty()
        visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }
}

private fun formLayout(context: Context) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
).apply {
    topMargin = (8 * context.resources.displayMetrics.density).toInt()
    bottomMargin = topMargin
}
