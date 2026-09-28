package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CompositionEditingRegressionTest {
    @Test fun repeatedBackspaceAfterSyllableNavigationNeverFallsThroughToTheEditor() = withEngine { engine ->
        val editing = engine as CompositionEditingEngine
        editing.restoreComposition("nihaoshijie")
        editing.selectSyllable()
        repeat(16) {
            if (engine.snapshot.isComposing) {
                val update = engine.handle(EngineKey.Backspace)
                assertTrue("Composing backspace must stay inside the engine", update.consumed)
                assertTrue("Deletion must never commit the preedit", update.committedText.isEmpty())
            }
        }
        assertFalse("Repeated deletion must empty the composition", engine.snapshot.isComposing)
    }

    @Test fun deletingAnAppendedLetterKeepsTheSelectedSegment() = withEngine { engine ->
        val editing = engine as CompositionEditingEngine
        editing.restoreComposition("nihao")
        editing.selectSyllable()
        val index = engine.snapshot.candidates.indexOfFirst { it.text == "你" }
        assertTrue(index >= 0)
        engine.selectCandidate(index)
        engine.handle(EngineKey.Character("x"))
        val result = engine.handle(EngineKey.Backspace)
        assertEquals("nihao", result.snapshot.rawInput)
        assertTrue(result.snapshot.canUndoSelection)
        assertTrue(result.committedText.isEmpty())
    }

    private fun withEngine(action: (InputEngine) -> Unit) {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            checkNotNull(graph.rime.createNativeOrNull()).use { engine ->
                engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                action(engine)
            }
        }.get(60, TimeUnit.SECONDS)
    }
}
