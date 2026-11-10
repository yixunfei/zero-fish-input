package dev.zeroinput.ime.ai

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.AiSkillRepository
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiExtensionDialogsTest {
    @Test fun summaryRequiresSeparateSendAndApplyAndCloseDiscardsLateResult() = host { activity ->
        val state = AiContextState(messages = List(4) { AiMessage(if (it % 2 == 0) AiRole.USER else AiRole.ASSISTANT, "Public $it") },
            selectedHistory = setOf(0, 1, 2, 3))
        var requests = 0; var applied = 0; var cancelled = 0
        var deliver: ((Result<AiCompactionProposal>) -> Unit)? = null
        fun review() = AiCompactionDialog(activity, AiCompactionInput(state),
            { requests++; deliver = it; true }, { applied++; true }, { cancelled++ })
        val first = review(); first.show()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(0, requests)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, requests); assertEquals(0, applied)
        deliver!!(Result.success(AiCompactionProposal(state.revision, "Public summary")))
        assertEquals(0, applied)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, applied)
        val second = review(); second.show()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        second.close()
        deliver!!(Result.success(AiCompactionProposal(state.revision, "Late public summary")))
        assertEquals(1, applied); assertEquals(1, cancelled)
        val third = review(); third.show()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        deliver!!(Result.success(AiCompactionProposal(state.revision, "Unapplied public summary")))
        third.close()
        assertEquals(1, applied); assertEquals(2, cancelled)
    }

    @Test fun unsupportedSkillsAreDisabledAndLibraryChangesRequireNewReview() = host { activity ->
        val repo = AiSkillRepository(object : EncryptedStore {
            var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
            override fun delete(deleteKey: Boolean) { bytes = null }
        })
        val safe = AiSkill("safe", "Public recipe", "", "Be concise")
        val unsupported = AiSkill("tool", "Needs tool", "", "Use file", listOf("files/read"))
        repo.write(0, AiSkillLibrary(skills = listOf(safe, unsupported))) { true }
        var applied = 0
        val review = AiSkillSelectionDialog(activity, repo, Executor { it.run() }, AiDataGeneration(), { it(); true },
            AiContextState(), { true }, {}, { applied++; true })
        try {
            review.show()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val boxes = descendants(dialog.findViewById(android.R.id.content)!!).filterIsInstance<CheckBox>()
            assertEquals(0, applied); assertFalse(boxes[1].isEnabled)
            boxes[0].performClick()
            repo.write(1, AiSkillLibrary(skills = listOf(AiSkill("safe", "Changed", "", "Changed instructions")))) { true }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(0, applied)
            val refreshed = descendants(dialog.findViewById(android.R.id.content)!!).filterIsInstance<CheckBox>().single()
            assertFalse(refreshed.isChecked)
            refreshed.performClick(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(1, applied)
        } finally { review.close() }
    }

    private fun host(block: (AppCompatActivity) -> Unit) {
        val owner = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = owner.get(); activity.setTheme(R.style.Theme_ZeroInput); owner.setup()
        try { block(activity) } finally { owner.pause().stop().destroy() }
    }
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
}
