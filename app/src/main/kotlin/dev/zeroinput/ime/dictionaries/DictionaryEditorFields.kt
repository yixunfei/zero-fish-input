package dev.zeroinput.ime.dictionaries

import android.content.Context
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText

internal fun dictionaryField(context: Context, hint: Int, limit: Int, initial: String = "") = EditText(context).apply {
    setHint(hint)
    setText(initial)
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    filters = arrayOf(InputFilter.LengthFilter(limit))
    maxLines = 3
    // Input type configuration resets TextView's minimum-line/height mode.
    minHeight = (48 * resources.displayMetrics.density).toInt()
    isSaveEnabled = false
    filterTouchesWhenObscured = true
    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
}
