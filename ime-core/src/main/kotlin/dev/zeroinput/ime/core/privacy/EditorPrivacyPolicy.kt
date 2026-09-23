package dev.zeroinput.ime.core.privacy

import android.text.InputType
import android.view.inputmethod.EditorInfo

data class PrivacyConfiguration(
    val learningEnabled: Boolean = true,
    val incognitoMode: Boolean = false,
    val excludedPackages: Set<String> = emptySet(),
)

data class SessionPrivacy(
    val isSensitive: Boolean,
    val suggestionsAllowed: Boolean,
    val learningAllowed: Boolean,
    val reason: PrivacyReason,
    val predictionsAllowed: Boolean = suggestionsAllowed,
) {
    /**
     * Personal data is a stricter boundary than engine-generated candidates.
     * A session that cannot learn must not read or mutate the personal store.
     */
    val personalizationAllowed: Boolean
        get() = learningAllowed && suggestionsAllowed
}

enum class PrivacyReason {
    NONE,
    PASSWORD_FIELD,
    EDITOR_REQUEST,
    IDENTIFIER_FIELD,
    INCOGNITO_MODE,
    USER_DISABLED,
    PACKAGE_EXCLUDED,
    UNKNOWN_EDITOR,
}

class EditorPrivacyPolicy {
    fun evaluate(editorInfo: EditorInfo, configuration: PrivacyConfiguration): SessionPrivacy {
        if (isPasswordField(editorInfo.inputType)) return restricted(PrivacyReason.PASSWORD_FIELD, true)
        if (!isKnownEditor(editorInfo.inputType)) return restricted(PrivacyReason.UNKNOWN_EDITOR, true)
        if (editorRequestsNoSuggestions(editorInfo.inputType)) {
            // This flag disables predictive suggestions, not conversion needed
            // to write Chinese. Password/unknown editors were rejected above;
            // public engine candidates remain usable without personal data.
            return restricted(PrivacyReason.EDITOR_REQUEST, false).copy(predictionsAllowed = false)
        }
        if (editorRequestsNoLearning(editorInfo)) return restricted(PrivacyReason.EDITOR_REQUEST, false)
        if (configuration.incognitoMode) return restricted(PrivacyReason.INCOGNITO_MODE, false)
        if (editorInfo.packageName in configuration.excludedPackages) {
            return restricted(PrivacyReason.PACKAGE_EXCLUDED, false)
        }
        if (!configuration.learningEnabled) {
            return SessionPrivacy(false, suggestionsAllowed = true, learningAllowed = false, PrivacyReason.USER_DISABLED)
        }
        if (isIdentifierField(editorInfo.inputType)) {
            return SessionPrivacy(false, suggestionsAllowed = true, learningAllowed = false, PrivacyReason.IDENTIFIER_FIELD)
        }
        return SessionPrivacy(false, suggestionsAllowed = true, learningAllowed = true, PrivacyReason.NONE)
    }

    private fun restricted(reason: PrivacyReason, sensitive: Boolean) = SessionPrivacy(
        isSensitive = sensitive,
        suggestionsAllowed = !sensitive,
        learningAllowed = false,
        reason = reason,
    )

    private fun editorRequestsNoLearning(editorInfo: EditorInfo): Boolean =
        editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    private fun editorRequestsNoSuggestions(inputType: Int): Boolean =
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0

    private fun isPasswordField(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in TEXT_PASSWORD_VARIATIONS
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun isIdentifierField(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return inputType and InputType.TYPE_MASK_VARIATION in IDENTIFIER_VARIATIONS
    }

    private fun isKnownEditor(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation in TEXT_VARIATIONS
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_NORMAL
            InputType.TYPE_CLASS_PHONE -> variation == 0
            InputType.TYPE_CLASS_DATETIME -> variation in DATETIME_VARIATIONS
            else -> false
        }
    }

    private companion object {
        val TEXT_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
            InputType.TYPE_TEXT_VARIATION_FILTER,
            InputType.TYPE_TEXT_VARIATION_PHONETIC,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )
        val DATETIME_VARIATIONS = setOf(
            InputType.TYPE_DATETIME_VARIATION_NORMAL,
            InputType.TYPE_DATETIME_VARIATION_DATE,
            InputType.TYPE_DATETIME_VARIATION_TIME,
        )
        val TEXT_PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val IDENTIFIER_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
    }
}
