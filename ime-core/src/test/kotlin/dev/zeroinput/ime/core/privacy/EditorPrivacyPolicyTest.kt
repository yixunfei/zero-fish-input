package dev.zeroinput.ime.core.privacy

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPrivacyPolicyTest {
    private val policy = EditorPrivacyPolicy()

    @Test
    fun `unknown editor classes and variations disable engines and personal data`() {
        val types = listOf(
            InputType.TYPE_NULL,
            0x0f,
            InputType.TYPE_CLASS_TEXT or 0x0ff0,
            InputType.TYPE_CLASS_NUMBER or 0x0ff0,
            InputType.TYPE_CLASS_PHONE or 0x0010,
            InputType.TYPE_CLASS_DATETIME or 0x0ff0,
        )
        for (type in types) {
            val result = policy.evaluate(EditorInfo().apply { inputType = type }, PrivacyConfiguration())
            assertTrue(result.isSensitive)
            assertFalse(result.suggestionsAllowed)
            assertFalse(result.personalizationAllowed)
        }
    }

    @Test
    fun `all credential variations disable engines and personal data`() {
        val types = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        for (type in types) {
            val result = policy.evaluate(EditorInfo().apply { inputType = type }, PrivacyConfiguration())
            assertTrue(result.isSensitive)
            assertFalse(result.suggestionsAllowed)
            assertFalse(result.personalizationAllowed)
        }
    }

    @Test
    fun `ordinary text keeps learning unless a user privacy setting forbids it`() {
        val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        assertTrue(policy.evaluate(editor, PrivacyConfiguration()).personalizationAllowed)
        assertFalse(policy.evaluate(editor, PrivacyConfiguration(incognitoMode = true)).personalizationAllowed)
        assertFalse(policy.evaluate(editor, PrivacyConfiguration(learningEnabled = false)).personalizationAllowed)
    }

    @Test
    fun `password fields disable suggestions and learning`() {
        val editor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val result = policy.evaluate(editor, PrivacyConfiguration())

        assertTrue(result.isSensitive)
        assertFalse(result.suggestionsAllowed)
        assertFalse(result.learningAllowed)
    }

    @Test
    fun `email fields keep engine suggestions but never use personalization`() {
        val editor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        val result = policy.evaluate(editor, PrivacyConfiguration())

        assertFalse(result.isSensitive)
        assertTrue(result.suggestionsAllowed)
        assertFalse(result.learningAllowed)
        assertFalse(result.personalizationAllowed)
    }

    @Test
    fun `URI fields never use personalization`() {
        val editor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        val result = policy.evaluate(editor, PrivacyConfiguration())

        assertTrue(result.suggestionsAllowed)
        assertFalse(result.learningAllowed)
        assertFalse(result.personalizationAllowed)
    }

    @Test
    fun `filter and autocomplete editors permit conversion only personalization`() {
        val filter = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
        }
        val autocomplete = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or
                InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE
        }

        for (editor in listOf(filter, autocomplete)) {
            val result = policy.evaluate(editor, PrivacyConfiguration())
            assertTrue(result.suggestionsAllowed)
            assertFalse(result.learningAllowed)
            assertFalse(result.personalizationAllowed)
            assertTrue(result.conversionPersonalizationAllowed)
            assertFalse(result.independentAiAllowed)
        }
    }

    @Test
    fun `no suggestions flag preserves conversion personalization without general learning`() {
        val editor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val result = policy.evaluate(editor, PrivacyConfiguration())

        assertFalse(result.isSensitive)
        assertTrue(result.suggestionsAllowed)
        assertFalse(result.learningAllowed)
        assertFalse(result.personalizationAllowed)
        assertFalse(result.predictionsAllowed)
        assertTrue(result.conversionPersonalizationAllowed)
    }

    @Test
    fun `no suggestions flag never permits conversion in password or unknown editors`() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0x0ff0)) {
            val editor = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            val result = policy.evaluate(editor, PrivacyConfiguration())
            assertTrue(result.isSensitive)
            assertFalse(result.suggestionsAllowed)
            assertFalse(result.personalizationAllowed)
            assertFalse(result.conversionPersonalizationAllowed)
        }
    }

    @Test
    fun `editor no personalized learning also hides personal data`() {
        val editor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }

        val result = policy.evaluate(editor, PrivacyConfiguration())

        assertFalse(result.personalizationAllowed)
        assertFalse(result.conversionPersonalizationAllowed)
        assertTrue(result.predictionsAllowed)
    }

    @Test fun `conversion exception never overrides credentials identifiers unknown or privacy opt outs`() {
        val restrictedTypes = listOf(
            InputType.TYPE_NULL, 0x0f, InputType.TYPE_CLASS_TEXT or 0x0ff0,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        val flags = listOf(0, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE,
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)
        for (type in restrictedTypes) for (flag in flags) {
            val result = policy.evaluate(EditorInfo().apply { inputType = type or flag }, PrivacyConfiguration())
            assertFalse(result.conversionPersonalizationAllowed)
            assertFalse(result.personalizationAllowed)
            assertFalse(result.independentAiAllowed)
        }
        for (variation in listOf(0, InputType.TYPE_TEXT_VARIATION_FILTER,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)) {
            for (flag in flags) {
                val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or variation or flag }
                for (configuration in listOf(PrivacyConfiguration(incognitoMode = true),
                    PrivacyConfiguration(learningEnabled = false))) {
                    assertFalse(policy.evaluate(editor, configuration).conversionPersonalizationAllowed)
                }
                editor.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                assertFalse(policy.evaluate(editor, PrivacyConfiguration()).conversionPersonalizationAllowed)
            }
        }
    }
}
