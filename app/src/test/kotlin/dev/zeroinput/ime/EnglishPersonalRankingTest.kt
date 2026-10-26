package dev.zeroinput.ime

import android.app.Application
import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PersonalSuggestion
import dev.zeroinput.engine.api.PersonalizationStore
import dev.zeroinput.engine.english.EnglishInputEngine
import dev.zeroinput.ime.core.EditorConnection
import dev.zeroinput.ime.core.InputCommand
import dev.zeroinput.ime.core.InputSessionController
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class EnglishPersonalRankingTest {
    @Test
    fun `third English choice promotes through the controller and keeps the native learning route`() {
        val store = Store()
        val connection = Connection()
        val controller = InputSessionController(
            connection,
            { EnglishInputEngine(lexicon = listOf("the", "there", "their")) },
            store,
        )
        controller.start(
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT },
            InputLanguage.ENGLISH,
            PrivacyConfiguration(),
        )

        repeat(3) {
            "th".forEach { controller.handle(InputCommand.Text(it.toString())) }
            if (store.frequency("their") < 3) {
                assertEquals(listOf("th", "the", "there", "their"),
                    controller.state.snapshot.candidates.map { it.text })
            } else {
                assertEquals("their", controller.state.snapshot.candidates.first().text)
            }
            controller.handle(InputCommand.SelectCandidate(
                controller.state.snapshot.candidates.indexOfFirst { it.text == "their" }))
        }
        "th".forEach { controller.handle(InputCommand.Text(it.toString())) }
        assertEquals("their", controller.state.snapshot.candidates.first().text)
        controller.handle(InputCommand.SelectCandidate(0))

        assertEquals(List(4) { "th" }, store.learnedShortcuts)
        assertEquals(List(4) { "their" }, store.learnedValues)
        assertEquals(List(4) { "their " }, connection.commits)
        assertEquals(0, store.personalRouteUses)
        controller.close()
    }

    @Test
    fun `third repeated English selection keeps the visible word and learns the same prefix and value`() {
        val store = Store()
        val connection = Connection()
        val controller = InputSessionController(connection,
            { EnglishInputEngine(lexicon = listOf("the", "there", "their")) }, store)
        controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT },
            InputLanguage.ENGLISH, PrivacyConfiguration())

        repeat(3) {
            "th".forEach { controller.handle(InputCommand.Text(it.toString())) }
            assertEquals(listOf("th", "the", "there", "their"),
                controller.state.snapshot.candidates.map { it.text })
            controller.handle(InputCommand.SelectCandidate(
                controller.state.snapshot.candidates.indexOfFirst { it.text == "there" }))
        }
        "th".forEach { controller.handle(InputCommand.Text(it.toString())) }
        assertEquals("there", controller.state.snapshot.candidates.first().text)
        controller.handle(InputCommand.SelectCandidate(0))

        assertEquals(List(4) { "there " }, connection.commits)
        assertEquals(List(4) { "th" }, store.learnedShortcuts)
        assertEquals(List(4) { "there" }, store.learnedValues)
        assertEquals(4, store.frequency("there"))
        assertEquals(0, store.personalRouteUses)
        controller.close()
    }

    private class Store : PersonalizationStore {
        var personalRouteUses = 0
        val learnedShortcuts = mutableListOf<String>()
        val learnedValues = mutableListOf<String>()
        private val entries = mutableMapOf<String, MutableMap<String, Int>>()

        fun frequency(value: String) = entries[value]?.values?.maxOrNull() ?: 0

        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) =
            entries.flatMap { (value, shortcuts) -> shortcuts.map { (shortcut, count) ->
                PersonalSuggestion("$value@$shortcut", value, count, shortcut)
            } }.filter { prefix.isNotEmpty() && it.input.startsWith(prefix) }.take(limit)

        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) {
            if (!learningAllowed) return
            learnedShortcuts += shortcut
            learnedValues += value
            entries.getOrPut(value, ::mutableMapOf)[shortcut] = frequency(value) + 1
        }

        override fun recordUse(id: String, learningAllowed: Boolean) {
            personalRouteUses++
        }
    }

    private class Connection : EditorConnection {
        val commits = mutableListOf<String>()
        override fun setComposingText(text: String) = Unit
        override fun finishComposingText() = Unit
        override fun commitText(text: String): Boolean {
            commits += text
            return true
        }
        override fun deleteBeforeCursor() = Unit
        override fun performEditorAction(actionId: Int) = false
        override fun sendEnterKey() = Unit
    }
}
