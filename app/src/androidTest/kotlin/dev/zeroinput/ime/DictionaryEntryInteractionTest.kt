package dev.zeroinput.ime

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser
import dev.zeroinput.ime.dictionaries.DictionaryEntryActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

@SdkSuppress(minSdkVersion = 29)
class DictionaryEntryInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = (context.applicationContext as ZeroInputApplication).graph
    private val id = "management_interaction_fixture"

    @Test fun addEditDeleteAndUndoArePersistedThroughTheEntryPage() {
        check(graph.publicDictionaries.list().none { it.id == id })
        check(graph.dictionaryEdits.read().entries.none { it.dictionaryId == id })
        graph.publicDictionaries.install(id, "Fixture", "ICE", "fixture",
            "---\nname: fixture\n...\n公开原词\tgong kai yuan ci\t42\n".byteInputStream(), RimeTextDictionaryParser())
        val activity = instrumentation.startActivitySync(Intent(context, DictionaryEntryActivity::class.java)
            .putExtra(DictionaryEntryActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DictionaryEntryActivity
        try {
            waitFor { visibleText("公开原词") }
            instrumentation.runOnMainSync {
                val toolbar = views(activity.window.decorView).filterIsInstance<MaterialToolbar>().single()
                toolbar.menu.performIdentifierAction(toolbar.menu.getItem(0).itemId, 0)
            }
            fill(R.string.dictionary_word, "公开新增词")
            fill(R.string.dictionary_reading, "gong kai xin zeng ci")
            fill(R.string.dictionary_weight, "99")
            clickLabel(R.string.save)
            waitFor { visibleText("公开新增词") && !hasEditor() }
            assertEquals(99, localEntries().single().replacement?.weight)

            clickText("公开新增词")
            fill(R.string.dictionary_word, "公开修改词")
            fill(R.string.dictionary_weight, "100")
            clickLabel(R.string.save)
            waitFor { visibleText("公开修改词") && !hasEditor() }
            assertEquals("公开修改词", localEntries().single().replacement?.text)
            assertEquals(100, localEntries().single().replacement?.weight)

            clickText("公开修改词")
            clickLabel(R.string.delete)
            clickLabel(R.string.delete)
            waitFor { !visibleText("公开修改词") && visibleText("公开原词") }
            assertTrue(localEntries().isEmpty())

            clickText("公开原词")
            clickLabel(R.string.delete)
            clickLabel(R.string.delete)
            waitFor { visibleText(context.getString(R.string.dictionary_deleted_entry, "公开原词", "gong kai yuan ci", 42)) }
            assertNull(localEntries().single().replacement)
            instrumentation.runOnMainSync {
                views(activity.window.decorView).filterIsInstance<MaterialSwitch>().single().isChecked = true
            }
            waitFor { visibleText(context.getString(R.string.dictionary_deleted_entry, "公开原词", "gong kai yuan ci", 42)) }
            clickText(context.getString(R.string.dictionary_deleted_entry, "公开原词", "gong kai yuan ci", 42))
            clickLabel(R.string.dictionary_undo_entry)
            waitFor { visibleText(context.getString(R.string.dictionary_no_entries)) && !hasEditor() }
            assertTrue(localEntries().isEmpty())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            graph.dictionaryEdits.reset(id)
            graph.publicDictionaries.remove(id)
        }
    }

    private fun localEntries() = graph.dictionaryEdits.read().entries.filter { it.dictionaryId == id }

    @Test fun resetRejectsAnOpenFormEvenBeforeItsUiRevocationIsDelivered() {
        check(graph.publicDictionaries.list().none { it.id == id })
        graph.publicDictionaries.install(id, "Fixture", "ICE", "fixture",
            "---\nname: fixture\n...\n公开原词\tgong kai yuan ci\t42\n".byteInputStream(), RimeTextDictionaryParser())
        val activity = instrumentation.startActivitySync(Intent(context, DictionaryEntryActivity::class.java)
            .putExtra(DictionaryEntryActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DictionaryEntryActivity
        val published = graph.dictionaryEdits.onPublished
        try {
            waitFor { visibleText("公开原词") }
            clickText("公开原词")
            fill(R.string.dictionary_word, "公开修改词")
            // Model a delayed UI notification; persistence must enforce revocation itself.
            graph.dictionaryEdits.onPublished = {}
            graph.dictionaryEdits.reset(id)
            clickLabel(R.string.save)
            waitFor {
                visibleText(context.getString(R.string.public_dictionary_failed)) || !hasEditor()
            }
            assertTrue("An old form must not recreate reset edits", localEntries().isEmpty())
        } finally {
            graph.dictionaryEdits.onPublished = published
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            graph.dictionaryEdits.reset(id)
            graph.publicDictionaries.remove(id)
        }
    }

    private fun fill(hint: Int, value: String) {
        instrumentation.runOnMainSync {
            visibleViews().filterIsInstance<EditText>().single { it.hint == context.getString(hint) }.setText(value)
        }
    }

    private fun clickLabel(label: Int) = clickText(context.getString(label), exact = true)

    private fun clickText(text: String, exact: Boolean = false) {
        waitFor { visibleViews().filterIsInstance<TextView>().any {
            it.isClickable && if (exact) it.text.toString() == text else it.text.startsWith(text)
        } }
        instrumentation.runOnMainSync {
            visibleViews().filterIsInstance<TextView>().last {
                it.isClickable && if (exact) it.text.toString() == text else it.text.startsWith(text)
            }.performClick()
        }
        instrumentation.waitForIdleSync()
    }

    private fun hasEditor() = visibleViews().filterIsInstance<EditText>().any { it.hint == context.getString(R.string.dictionary_word) }
    private fun visibleText(text: String) = visibleViews().filterIsInstance<TextView>().any { it.text.startsWith(text) }
    private fun visibleViews() = WindowInspector.getGlobalWindowViews().flatMap(::views).filter { it.isShown }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            var complete = false
            instrumentation.runOnMainSync { complete = condition() }
            if (complete) return
            Thread.sleep(50)
        }
        fail("Dictionary interaction did not complete")
    }
}
