package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.UserLexiconRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserLexiconFrequencyTest {
    @Test fun accumulatedFrequencyCannotOverflowWhenManyReadingsShareOneWord() {
        val terms = JSONArray().apply {
            repeat(2_148) { index ->
                put(JSONObject().apply {
                    put("id", "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
                    put("shortcut", "reading$index")
                    put("value", "fixture")
                    put("language", "ENGLISH")
                    put("frequency", 1_000_000)
                    put("lastUsed", 0)
                })
            }
        }
        val bytes = JSONObject().put("format", 1).put("terms", terms).toString().toByteArray()
        val store = object : EncryptedStore {
            override fun read() = bytes.copyOf()
            override fun write(plaintext: ByteArray) = error("Read-only fixture")
            override fun delete(deleteKey: Boolean) = error("Read-only fixture")
        }

        val frequencies = UserLexiconRepository(store).frequenciesFor(listOf("fixture"), InputLanguage.ENGLISH)

        assertEquals(Int.MAX_VALUE, frequencies["fixture"])
    }
}
