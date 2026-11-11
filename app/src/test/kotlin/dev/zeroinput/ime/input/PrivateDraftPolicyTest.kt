package dev.zeroinput.ime.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.ime.core.privacy.EditorPrivacyPolicy
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivateDraftPolicyTest {
    @Test fun eligibleTextAllowsIndependentDraftWithLearningOff() {
        assertTrue(allows(InputType.TYPE_CLASS_TEXT, config = PrivacyConfiguration(learningEnabled = false)))
        assertTrue(allows(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }

    @Test fun ordinaryTextWithPredictionsOffCanUsePrivateDraft() {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertTrue(allows(type))
        assertFalse(allows(type, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertFalse(allows(type, config = PrivacyConfiguration(incognitoMode = true)))
    }

    @Test fun identifiersAndRestrictedFieldsNeverUseDefaultDraft() {
        val restricted = listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI)
        for (variation in restricted) {
            assertFalse(allows(InputType.TYPE_CLASS_TEXT or variation, config = PrivacyConfiguration(learningEnabled = false)))
        }
        assertFalse(allows(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(allows(InputType.TYPE_NULL))
        assertFalse(allows(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertFalse(allows(InputType.TYPE_CLASS_TEXT, config = PrivacyConfiguration(incognitoMode = true)))
    }

    private fun allows(type: Int, options: Int = 0, config: PrivacyConfiguration = PrivacyConfiguration()): Boolean {
        val info = EditorInfo().apply { inputType = type; imeOptions = options }
        return isPrivateDraftEditor(info, EditorPrivacyPolicy().evaluate(info, config), config.incognitoMode)
    }
}
