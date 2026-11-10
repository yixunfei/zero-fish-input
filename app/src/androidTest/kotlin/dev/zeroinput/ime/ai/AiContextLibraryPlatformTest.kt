package dev.zeroinput.ime.ai

import android.content.Intent
import android.view.WindowManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.*
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.userdata.AiContextLibraryRepository
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiContextLibraryPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun skillManagerIgnoresIncomingContentAndUsesAPrivateWindow() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, AiSkillsActivity::class.java)
            .putExtra(Intent.EXTRA_TEXT, "Untrusted public fixture")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AiSkillsActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertNull(activity.intent.getStringExtra(Intent.EXTRA_TEXT))
                val info = activity.packageManager.getActivityInfo(activity.componentName, 0)
                assertFalse(info.exported)
                assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun skillsUseAnIndependentEncryptedFixtureAndDeleteItsKey() {
        val store = EncryptedFileStore(instrumentation.targetContext, "skills-platform-fixture.bin", "zeroinput.test.skills")
        val repository = dev.zeroinput.userdata.AiSkillRepository(store)
        try {
            repository.clear()
            val skill = AiSkill("public", "Public fixture", "", "Use concise prose", listOf("files/read"))
            repository.write(0, AiSkillLibrary(skills = listOf(skill))) { true }
            assertEquals(skill.instructions, repository.read().skills.single().instructions)
            assertEquals(skill.requiredTools, repository.read().skills.single().requiredTools)
            repository.clear()
            assertTrue(repository.read().skills.isEmpty())
        } finally { repository.clear() }
    }

    @Test fun dedicatedEncryptedStoreRoundTripsAndDeletesOnlyItsFixture() {
        // Separate file/key: never read or clear the user's context library.
        val store = EncryptedFileStore(instrumentation.targetContext, "context-platform-fixture.bin", "zeroinput.test.context-library")
        val repository = AiContextLibraryRepository(store)
        try {
            repository.clear()
            val role = AiPersona("public", "Public fixture", "", "Use concise prose")
            repository.write(0, AiContextLibrary(personas = listOf(role),
                memories = listOf(AiMemory("fact", role.id, "Public fact")))) { true }
            assertEquals(role, repository.read().personas.single())
            assertEquals("Public fact", repository.read().memories.single().text)
            repository.clear()
            assertTrue(repository.read().personas.isEmpty())
        } finally { repository.clear() }
    }

    @Test fun privateReviewRequiresExplicitApplyAndClosingRevokesPendingLoad() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, AiAssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AiAssistantActivity
        val pending = ArrayDeque<Runnable>()
        var applied = 0
        var readCount = 0
        val repository = AiContextLibraryRepository(object : dev.zeroinput.security.EncryptedStore {
            override fun read(): ByteArray? { readCount++; return null }
            override fun write(plaintext: ByteArray) = Unit
            override fun delete(deleteKey: Boolean) = Unit
        })
        try {
            instrumentation.runOnMainSync {
                val prompt = activity.findViewById<EditText>(dev.zeroinput.ime.ui.R.id.ai_assistant_prompt)
                prompt.setText("Public unsent draft")
                val review = AiContextLibraryDialog(activity, repository, Executor { pending.add(it) }, AiDataGeneration(),
                    { it(); true }, AiContextState(), { true }, {}, { applied++; true })
                review.show()
                assertEquals(0, readCount); assertEquals(0, applied)
                review.close()
                while (pending.isNotEmpty()) pending.removeFirst().run()
                assertEquals(0, readCount); assertEquals(0, applied)
                assertEquals("Public unsent draft", prompt.text.toString())
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
