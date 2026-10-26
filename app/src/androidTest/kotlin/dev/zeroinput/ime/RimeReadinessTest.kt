package dev.zeroinput.ime

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PublicResourceLease
import dev.zeroinput.engine.api.PublicResourceSource
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
                File(root, "rime").listFiles().orEmpty().filter { it.name.startsWith("user-") }
                    .forEach { check(File(it, "build").deleteRecursively()) }

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
        }.get(600, TimeUnit.SECONDS)
    }

    @Test fun closeAndLastSessionReleaseAllowLaterNativeRetry() = withFixture { factory, _ ->
        assertTrue(factory.warmUp())
        requireNotNull(factory.createNativeOrNull()).use { engine ->
            engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
            "ni".forEach { engine.handle(EngineKey.Character(it.toString())) }
            assertTrue(engine.snapshot.candidates.isNotEmpty())
        }
        factory.close()
        assertEquals(RimeRuntimeState.NOT_INITIALIZED, factory.runtime.state)
        assertTrue("A closed native runtime must be retryable", factory.warmUp())
    }

    @Test fun closeThenLastActiveSessionReleaseLeavesRetryImmediatelyAvailable() = withFixture { factory, _ ->
        assertTrue(factory.warmUp())
        val engine = requireNotNull(factory.createNativeOrNull())
        engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
        factory.close()
        engine.close()
        assertEquals(RimeRuntimeState.NOT_INITIALIZED, factory.runtime.state)
        assertTrue("Releasing the last closed-runtime session must make the next retry usable",
            factory.warmUp())
    }

    @Test fun cancelledQueuedWarmupAndClosedRunningWarmupPreserveRetryOwnership() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit { graph.rime.close() }.get(30, TimeUnit.SECONDS)
        val root = File(context.cacheDir, "rime-cancel-${System.nanoTime()}").apply { mkdirs() }
        check(root.isDirectory)
        val worker = dev.zeroinput.ime.concurrency.BoundedExecutors.singleThread("fixture-cancel", 2)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val dictionaries = object : dev.zeroinput.engine.api.PublicDictionarySource {
            override fun enabledFiles() = emptyList<dev.zeroinput.engine.api.PublicDictionaryFile>()
            override fun <T> withSnapshot(consume: (dev.zeroinput.engine.api.PublicDictionarySnapshot) -> T): T {
                entered.countDown()
                check(release.await(60, TimeUnit.SECONDS))
                return consume(dev.zeroinput.engine.api.PublicDictionarySnapshot(emptyList()))
            }
        }
        try {
            RimeEngineFactory(FixtureContext(context, root), worker, dictionaries = dictionaries,
                resources = object : PublicResourceSource {
                    override val revision = 0L
                    override fun acquire(id: String): PublicResourceLease? = null
                }).use { factory ->
                val running = worker.submit<Boolean> { factory.warmUp() }
                assertTrue(entered.await(30, TimeUnit.SECONDS))
                factory.close()
                val queued = worker.submit<Boolean> { factory.warmUp() }
                assertTrue(queued.cancel(false))
                release.countDown()
                assertFalse("Closing during initialization must invalidate that generation", running.get(60, TimeUnit.SECONDS))
                assertEquals(RimeRuntimeState.NOT_INITIALIZED, factory.runtime.state)
                assertTrue("A fresh queued retry must succeed after the original worker returns", factory.warmUp())
                requireNotNull(factory.createNativeOrNull()).close()
            }
        } finally {
            release.countDown()
            worker.shutdownNow()
            check(worker.awaitTermination(60, TimeUnit.SECONDS))
            graph.engineExecutor.submit { graph.rime.close(); check(graph.rime.warmUp()) }.get(300, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    @Test fun corruptedDictionaryRecoveryAndCloseDoNotPoisonNextGeneration() = withFixture { factory, root ->
        assertTrue(factory.warmUp())
        factory.close()
        val user = File(root, "rime").listFiles().orEmpty().single { it.name.startsWith("user-") }
        val build = File(user, "build")
        build.listFiles().orEmpty().forEach { check(it.delete()) }
        File(build, "zeroinput_public.table.bin").writeBytes(byteArrayOf(0, 1, 2, 3))

        assertTrue("Corruption must either recover in a bounded worker or leave a working fallback",
            factory.warmUp() || factory.create().descriptor.isFallback)
        assertTrue("Runtime must publish readiness only for a verified session",
            factory.runtime.isReady || factory.runtime.state == RimeRuntimeState.FAILED)
        factory.close()
        assertTrue("Closing after recovery or failed verification must leave native retry possible", factory.warmUp())
        requireNotNull(factory.createNativeOrNull()).close()
    }

    private fun withFixture(test: (RimeEngineFactory, File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        instrumentation.runOnMainSync { graph.rime.close() }
        val root = File(context.cacheDir, "rime-retry-${System.nanoTime()}").apply { mkdirs() }
        val fixture = FixtureContext(context, root)
        val noOptionalData = object : PublicResourceSource {
            override val revision = 0L
            override fun acquire(id: String): PublicResourceLease? = null
        }
        try {
            RimeEngineFactory(fixture, graph.engineExecutor, resources = noOptionalData).use { factory ->
                graph.engineExecutor.submit { test(factory, root) }.get(180, TimeUnit.SECONDS)
            }
        } finally {
            graph.engineExecutor.submit { graph.rime.close(); check(graph.rime.warmUp()) }.get(300, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    private class FixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root.apply { mkdirs() }
    }
}
