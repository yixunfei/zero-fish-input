package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.DoublePinyinScheme
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RimeDoublePinyinTest {
    @Test
    fun bothSchemesDeployAndConvertPublicPhrase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            graph.rime.close()
            assertTrue(graph.rime.warmUp())
            for (scheme in listOf(DoublePinyinScheme.MICROSOFT, DoublePinyinScheme.ZIRANMA)) {
                val engine = graph.rime.createNativeOrNull(ChineseInputOptions(doublePinyinScheme = scheme))
                assertNotNull("Native Rime should deploy $scheme: ${graph.rime.runtime.initializationError?.message}", engine)
                engine!!.use {
                    it.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                    assertConversion(it, "nihk", "你好", scheme)
                    assertConversion(it, if (scheme == DoublePinyinScheme.MICROSOFT) "m;" else "my", "明", scheme)
                    val editing = it as dev.zeroinput.engine.api.CompositionEditingEngine
                    val cursor = it as dev.zeroinput.engine.api.CompositionCursorEngine
                    val code = if (scheme == DoublePinyinScheme.MICROSOFT) "m;" else "my"
                    assertTrue(editing.restoreComposition(code).consumed)
                    cursor.selectComposition(dev.zeroinput.engine.api.CompositionSelection(1, 2))
                    assertEquals(code, it.handle(EngineKey.Character(code.last().toString())).snapshot.rawInput)
                    assertEquals(dev.zeroinput.engine.api.CompositionSelection(2), it.snapshot.selection)
                    cursor.selectComposition(dev.zeroinput.engine.api.CompositionSelection(1))
                    assertEquals("m" + "x" + code.last(), it.handle(EngineKey.Character("x")).snapshot.rawInput)
                }
            }
        }.get(90, TimeUnit.SECONDS)
    }

    private fun assertConversion(engine: InputEngine, code: String, expected: String, scheme: DoublePinyinScheme) {
        engine.reset()
        code.forEach { key -> engine.handle(EngineKey.Character(key.toString())) }
        val index = engine.snapshot.candidates.indexOfFirst { candidate -> candidate.text == expected }
        assertTrue("Public conversion should be available in $scheme", index >= 0)
        assertEquals(expected, engine.selectCandidate(index).committedText)
    }
}
