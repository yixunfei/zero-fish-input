package dev.zeroinput.ime.textstego

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.security.message.MessageCipher
import dev.zeroinput.security.message.MessageCredential
import dev.zeroinput.security.message.MessageCredentialMode
import dev.zeroinput.security.message.MessageKdf
import dev.zeroinput.security.message.MessageStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShortProsePlatformTest {
    @Test fun eightHanDraftRoundTripsWithinTheMeasuredProseBudgets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.targetContext.assets
        // The archived Debug APKs expose the same codec entry points with an earlier prose picker.
        val archivedDaily = InstrumentationRegistry.getArguments().getString("archivedDaily") == "true"
        val selected = StegoWordingProfile.valueOf(if (archivedDaily) "DAILY" else "PROSE_WINDOW")
        val draft = "窗前新叶随风轻动".toCharArray()
        val passphrase = java.util.UUID.randomUUID().toString().toCharArray()
        val window = if (archivedDaily) null else VerseProfileAssets(assets).window.surface
        val aes = listOf(MessageStage(MessageCipher.AES))
        try {
            MessageCredential(MessageCredentialMode.PASSPHRASE, passphrase).use { key ->
                for ((stages, budget) in listOf(aes to 4000, (aes + MessageStage(MessageCipher.XCHACHA20)) to 5400)) {
                    val made = StegoWordingCodec.seal(assets, selected, draft, key, stages, MessageKdf.STANDARD,
                        randomWidths = false, current = { true })
                    val reopened = StegoWordingCodec.open(assets, selected, made.text, key, { true }) {
                        it.mode == MessageCredentialMode.PASSPHRASE && it.kdf == MessageKdf.STANDARD
                    }
                    try {
                        assertTrue(reopened != null && draft.contentEquals(reopened))
                    } finally { reopened?.fill('\u0000') }
                    val envelope = window?.operation { true }?.decode(made.text)
                    try {
                        assertEquals(24, made.originalBytes)
                        assertEquals(made.text.toByteArray(Charsets.UTF_8).size, made.utf8Bytes)
                        assertTrue(made.utf8Bytes <= budget)
                        instrumentation.sendStatus(2, Bundle().apply {
                            putInt("stage_count", stages.size)
                            putInt("body_bytes", made.originalBytes)
                            if (envelope != null) putInt("envelope_bytes", envelope.size)
                            putInt("carrier_bytes", made.utf8Bytes)
                        })
                    } finally { envelope?.fill(0) }
                }
            }
        } finally {
            draft.fill('\u0000')
            passphrase.fill('\u0000')
        }
    }
}
