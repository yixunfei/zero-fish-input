package dev.zeroinput.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import dev.zeroinput.ime.ui.ZeroInputView

/** Displays only non-sensitive editor metadata, and only in Debug builds. */
internal object InputDiagnostics {
    internal data class EditorMetadata(
        val packageName: String,
        val inputType: Int,
        val imeOptions: Int,
        val subtype: String,
    ) {
        val inputClass: String = when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> "text"
            InputType.TYPE_CLASS_NUMBER -> "number"
            InputType.TYPE_CLASS_PHONE -> "phone"
            InputType.TYPE_CLASS_DATETIME -> "datetime"
            else -> "unknown"
        }

        val variation: Int = inputType and InputType.TYPE_MASK_VARIATION
    }

    @Suppress("DEPRECATION")
    fun capture(editor: EditorInfo, subtype: InputMethodSubtype?): EditorMetadata {
        return EditorMetadata(
            packageName = editor.packageName ?: "unknown",
            inputType = editor.inputType,
            imeOptions = editor.imeOptions,
            subtype = subtypeLabel(subtype),
        )
    }

    @Suppress("DEPRECATION")
    fun subtypeLabel(subtype: InputMethodSubtype?): String = subtype?.let {
            sequenceOf(it.languageTag, it.locale)
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
        } ?: "none"

    fun render(view: ZeroInputView?, text: String) {
        if (BuildConfig.DEBUG) view?.renderDiagnostics(text)
    }

    fun clear(view: ZeroInputView?) {
        if (BuildConfig.DEBUG) view?.renderDiagnostics(null)
    }
}
