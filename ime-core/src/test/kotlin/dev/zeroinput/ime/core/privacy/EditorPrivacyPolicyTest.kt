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
    fun `no suggestions flag preserves conversion but disables personalization`() {
        val editor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val result = policy.evaluate(editor, PrivacyConfiguration())

        assertFalse(result.isSensitive)
        assertTrue(result.suggestionsAllowed)
        assertFalse(result.learningAllowed)
        assertFalse(result.personalizationAllowed)
        assertFalse(result.predictionsAllowed)
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
        assertTrue(result.predictionsAllowed)
    }
}
