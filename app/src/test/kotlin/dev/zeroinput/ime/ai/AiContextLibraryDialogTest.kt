package dev.zeroinput.ime.ai

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.AiContextLibraryRepository
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiContextLibraryDialogTest {
    @Test fun navigationAndSaveDoNotApplyUntilExplicitSelectionAndReview() {
        val host = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = host.get(); activity.setTheme(R.style.Theme_ZeroInput); host.setup()
        val repo = repository()
        val applied = mutableListOf<AiWorkspaceContext>()
        val review = AiContextLibraryDialog(activity, repo, Executor { it.run() }, AiDataGeneration(),
            { it(); true }, AiContextState(), { true }, {}, { applied += it; true })
        try {
            review.show()
            val main = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(main.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            find(main, activity.getString(R.string.ai_library_new_role)).performClick()
            val editor = ShadowDialog.getLatestDialog() as AlertDialog
            val inputs = descendants(editor.findViewById(android.R.id.content)!!).filterIsInstance<EditText>()
            inputs[0].setText("Public role"); inputs[2].setText("Use concise prose")
            assertTrue(inputs.all { !it.isSaveEnabled })
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals("Public role", repo.read().personas.single().name)
            assertTrue(applied.isEmpty())
            find(main, "Public role").performClick()
            find(main, activity.getString(R.string.ai_library_new_memory)).performClick()
            val memory = ShadowDialog.getLatestDialog() as AlertDialog
            val memoryViews = descendants(memory.findViewById(android.R.id.content)!!)
            assertFalse(memoryViews.filterIsInstance<CheckBox>().single().isChecked)
            memoryViews.filterIsInstance<EditText>().single().setText("Public fact")
            memory.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(repo.read().personas.single().id, repo.read().memories.single().personaId)
            assertTrue(applied.isEmpty())
            descendants(main.findViewById(android.R.id.content)!!).filterIsInstance<CheckBox>().single().performClick()
            main.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals("Public fact", applied.single().memories.single().text)
        } finally { review.close(); host.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun find(dialog: AlertDialog, text: String): View = descendants(dialog.findViewById(android.R.id.content)!!)
        .filterIsInstance<TextView>().first { it.text.toString() == text }
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
    private fun repository() = AiContextLibraryRepository(object : EncryptedStore {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
        override fun delete(deleteKey: Boolean) { bytes = null }
    })
}
