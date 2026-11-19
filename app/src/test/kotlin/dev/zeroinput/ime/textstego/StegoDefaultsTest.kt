package dev.zeroinput.ime.textstego

import android.content.Context
import dev.zeroinput.ime.settings.SettingsRepository
import dev.zeroinput.ime.ui.StegoField
import dev.zeroinput.security.message.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StegoDefaultsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("zeroinput-settings", Context.MODE_PRIVATE)
    @Before fun reset() { preferences.edit().clear().commit() }

    @Test fun defaultsAllowVaultButRequireExplicitSystemOptIn() {
        val value = SettingsRepository(context).stegoDefaults
        assertEquals(256, value.masterBits)
        assertEquals(listOf(MessageStage(MessageCipher.AES)), value.stages)
        assertTrue(value.vaultPaste); assertFalse(value.systemPaste)
    }

    @Test fun selectedWidthsAndOrderSurviveRepositoryRecreationWithoutSavingCredentials() {
        val expected = StegoDefaults(264, listOf(MessageStage(MessageCipher.SM4), MessageStage(MessageCipher.AES, 192)), false, true)
        SettingsRepository(context).stegoDefaults = expected
        assertEquals(expected, SettingsRepository(context).stegoDefaults)
        assertEquals(setOf("stego.default-master-bits", "stego.default-stages", "stego.vault-paste", "stego.system-paste"), preferences.all.keys)
    }

    @Test fun malformedOrUnsupportedCipherDefaultsUseOneValidBuiltInCascade() {
        for (raw in listOf("", "1:128,1:256", "7:256", "6:256", "2:128", "1", "1:256,", "1:256,".repeat(20))) {
            preferences.edit().putString("stego.default-stages", raw).commit()
            assertEquals(listOf(MessageStage(MessageCipher.AES)), SettingsRepository(context).stegoDefaults.stages)
        }
    }

    @Test fun invalidDefaultsCannotReplaceAnExistingValidConfiguration() {
        val settings = SettingsRepository(context)
        val initial = settings.stegoDefaults
        for (value in listOf(initial.copy(masterBits = 257), initial.copy(stages = emptyList()),
                initial.copy(stages = initial.stages + initial.stages))) {
            try { settings.stegoDefaults = value; fail("Invalid defaults must reject") } catch (_: IllegalArgumentException) { }
            assertEquals(initial, settings.stegoDefaults)
        }
    }

    @Test fun wrongPreferenceTypesDoNotGrantSystemPasteOrCrash() {
        preferences.edit().putString("stego.default-master-bits", "bad").putInt("stego.default-stages", 4)
            .putString("stego.system-paste", "true").putString("stego.vault-paste", "true").commit()
        val defaults = SettingsRepository(context).stegoDefaults
        assertEquals(256, defaults.masterBits); assertEquals(1, defaults.stages.size)
        assertFalse(defaults.systemPaste || defaults.vaultPaste)
    }

    @Test fun textPasteChecksUtf8BudgetWithoutTruncatingOrAcceptingCredentials() {
        assertTrue(StegoPastePolicy.accepts(StegoField.SECRET, "中".repeat(682)))
        assertFalse(StegoPastePolicy.accepts(StegoField.SECRET, "中".repeat(683)))
        assertTrue(StegoPastePolicy.accepts(StegoField.SECRET, "x".repeat(2048)))
        assertFalse(StegoPastePolicy.accepts(StegoField.SECRET, "x".repeat(2049)))
        assertTrue(StegoPastePolicy.accepts(StegoField.COVER, "中".repeat(512)))
        assertFalse(StegoPastePolicy.accepts(StegoField.COVER, "x".repeat(513)))
        for (field in StegoField.entries) {
            assertFalse(StegoPastePolicy.accepts(field, "bad\u0000text"))
            assertFalse(StegoPastePolicy.accepts(field, "\uD800"))
            assertFalse(StegoPastePolicy.accepts(field, ""))
        }
        assertFalse(StegoPastePolicy.accepts(StegoField.CREDENTIAL, "key"))
        assertFalse(StegoPastePolicy.accepts(StegoField.KEY_BITS, "256"))
    }
}
