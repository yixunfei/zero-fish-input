package dev.zeroinput.userdata

import dev.zeroinput.ai.api.*
import dev.zeroinput.security.EncryptedStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSkillRepositoryTest {
    private val skill = AiSkill("public-id", "Public", "Fixture", "Use explicit text", listOf("files/read"))
    private val value = AiSkillLibrary(skills = listOf(skill))

    @Test fun roundTripWipesTemporaryBuffersAndRejectsStaleWrites() {
        val store = Store(); val repo = AiSkillRepository(store)
        val saved = repo.write(0, value) { true }
        assertEquals(1L, saved.revision)
        val loaded = repo.read().skills.single()
        assertEquals(skill.instructions, loaded.instructions)
        assertEquals(skill.requiredTools, loaded.requiredTools)
        assertTrue(checkNotNull(store.readBuffer).all { it == 0.toByte() })
        assertTrue(checkNotNull(store.writeBuffer).all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { repo.write(0, AiSkillLibrary()) { true } }
        assertEquals(1, repo.read().skills.size)
    }

    @Test fun corruptionUnknownFieldsAndOversizedDataAreRejectedWithRedactedErrors() {
        val store = Store(); val repo = AiSkillRepository(store)
        for (text in listOf("{", "{\"format\":1,\"format\":1}",
            "{\"format\":2,\"revision\":0,\"skills\":[]}",
            "{\"format\":1,\"revision\":0,\"skills\":[],\"private\":\"fixture\"}",
            "{\"format\":1,\"revision\":-1,\"skills\":[]}",
            "{\"format\":1,\"revision\":\"0\",\"skills\":[]}", "x".repeat(1_048_577))) {
            store.bytes = text.toByteArray()
            assertEquals("Skill library unavailable", assertThrows(IllegalStateException::class.java) { repo.read() }.message)
            assertTrue(checkNotNull(store.readBuffer).all { it == 0.toByte() })
        }
        store.bytes = byteArrayOf(0xc3.toByte(), 0x28)
        assertThrows(IllegalStateException::class.java) { repo.read() }
    }

    @Test fun revokedEditsAndFailedDeletionCannotResurrectSkills() {
        val store = Store(); val repo = AiSkillRepository(store)
        repo.write(0, value) { true }
        assertThrows(IllegalStateException::class.java) { repo.write(1, AiSkillLibrary()) { false } }
        store.failWrite = true
        assertThrows(IllegalStateException::class.java) { repo.write(1, AiSkillLibrary()) { true } }
        store.failWrite = false
        assertEquals(1, repo.read().skills.size)
        store.failDelete = true
        assertThrows(IllegalStateException::class.java) { repo.clear() }
        assertThrows(IllegalStateException::class.java) { repo.read() }
        assertThrows(IllegalStateException::class.java) { repo.write(1, value) { true } }
        store.failDelete = false; repo.clear()
        assertTrue(store.keyDeleted); assertTrue(repo.read().skills.isEmpty())
    }

    private class Store : EncryptedStore {
        var bytes: ByteArray? = null
        var readBuffer: ByteArray? = null
        var writeBuffer: ByteArray? = null
        var failWrite = false
        var failDelete = false
        var keyDeleted = false
        override fun read() = bytes?.copyOf()?.also { readBuffer = it }
        override fun write(plaintext: ByteArray) { writeBuffer = plaintext; check(!failWrite); bytes = plaintext.copyOf() }
        override fun delete(deleteKey: Boolean) { check(!failDelete); bytes = null; keyDeleted = deleteKey }
    }
}
