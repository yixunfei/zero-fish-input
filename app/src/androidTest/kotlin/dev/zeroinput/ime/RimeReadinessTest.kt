package dev.zeroinput.ime

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.rime.RimeEngineFactory
import dev.zeroinput.engine.rime.RimeRuntimeState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Fault injection touches only a disposable copy of public Rime assets. */
@RunWith(AndroidJUnit4::class)
class RimeReadinessTest {
    @Test fun missingDictionaryCannotReportReadyAndFallbackStillConvertsChinese() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            // Run through tools/test-input-experience.ps1 so no live IME owns native sessions.
            graph.rime.close()
            val root = File(context.noBackupFilesDir, "rime-readiness-fixture")
            check(!root.exists()) { "Public readiness fixture already exists" }
            val fixture = FixtureContext(context, root)
            val factory = RimeEngineFactory(fixture, graph.engineExecutor)
            try {
                assertTrue(factory.warmUp())
                factory.close()
                val shared = File(root, "rime").listFiles().orEmpty().single { it.name.startsWith("shared-") }
                val schemaFile = File(shared, "zeroinput_pinyin.schema.yaml")
                val schema = JSONObject(schemaFile.readText())
                schema.getJSONObject("translator").put("dictionary", "missing_public_fixture")
                schema.getJSONObject("schema").put("version", "999.0")
                schemaFile.writeText(schema.toString())
                check(File(root, "rime/user/build").deleteRecursively())

                assertFalse("Session creation alone must not mark an unusable dictionary ready", factory.warmUp())
                assertEquals(RimeRuntimeState.FAILED, factory.runtime.state)
                factory.create().use { engine ->
                    assertTrue(engine.descriptor.isFallback)
                    engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                    "nihao".forEach { engine.handle(EngineKey.Character(it.toString())) }
                    val selected = engine.snapshot.candidates.indexOfFirst { it.text == "你好" }
                    assertTrue(selected >= 0)
                    assertEquals("你好", engine.selectCandidate(selected).committedText)
                }
            } finally {
                factory.close()
                check(root.deleteRecursively())
                // Native runtime is process-wide. Restore the application's public
                // assets so later real-editor tests do not inherit this fault fixture.
                check(graph.rime.warmUp()) { "Application runtime restoration failed" }
            }
        }.get(60, TimeUnit.SECONDS)
    }

    private class FixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root.apply { mkdirs() }
    }
}
