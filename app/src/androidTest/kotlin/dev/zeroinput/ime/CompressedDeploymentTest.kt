package dev.zeroinput.ime

import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PublicResourceLease
import dev.zeroinput.engine.api.PublicResourceSource
import dev.zeroinput.engine.rime.RimeEngineFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class CompressedDeploymentTest {
    @Test fun reviewedDefaultIdentityMatchesRuntimeInputs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = context.assets.open("rime/public-dictionaries.json").bufferedReader().use {
            org.json.JSONObject(it.readText())
        }
        val files = manifest.getJSONObject("files")
        val sourceText = files.keys().asSequence().sorted().joinToString { it + files.getString(it) }
        val source = java.security.MessageDigest.getInstance("SHA-256").digest(sourceText.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        val expected = context.assets.open("rime/prebuilt/manifest.json").bufferedReader().use {
            org.json.JSONObject(it.readText())
        }
        dev.zeroinput.engine.api.PublicDictionarySource.Empty.withSnapshot { snapshot ->
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(
                ("11" + source + "none" + snapshot.extraFiles.sortedBy { it.id }.joinToString { it.id + it.sha256 } +
                    snapshot.disabledBuiltInTables.sorted().joinToString() + snapshot.builtInReplacements.toSortedMap()
                        .entries.joinToString { it.key + it.value.sha256 }).toByteArray())
                .joinToString("") { "%02x".format(it) }.take(24)
            assertEquals("Public source identity must agree with the reviewed generator",
                expected.getString("sourceIdentity"), source)
            assertEquals("Runtime default identity inputs must agree with the reviewed generator; " +
                "extras=${snapshot.extraFiles.size}, disabled=${snapshot.disabledBuiltInTables}, " +
                "replacements=${snapshot.builtInReplacements.keys}", expected.getString("generation"), actual)
        }
        assertFalse(context.assets.list("rime").orEmpty().contains("wanxiang-lts-zh-hans.gram"))
    }

    @Test fun freshWorkerDeploymentPreservesAllTablesAndReusesVerifiedGeneration() = withFixture(times = 3) { factory, root, context ->
        val started = SystemClock.elapsedRealtime()
        if (!factory.warmUp()) {
            throw AssertionError("Public fixture deployment failed", factory.runtime.initializationError)
        }
        val cold = SystemClock.elapsedRealtime() - started
        assertTrue("Fresh default startup must retain the 60-second acceptance budget; measured=${cold}ms", cold < 60_000)
        val base = File(root, "rime").listFiles().orEmpty().single { it.name.startsWith("base-11-") }
        val tables = File(base, "dicts").listFiles().orEmpty()
        assertEquals(16, tables.size)
        assertTrue(tables.all { it.name.endsWith(".dict.yaml") })
        val modified = tables.associate { it.name to it.lastModified() }
        val shared = File(root, "rime").listFiles().orEmpty().single { it.name.startsWith("shared-") }
        val expectedGeneration = context.assets.open("rime/prebuilt/manifest.json").bufferedReader().use {
            org.json.JSONObject(it.readText()).getString("generation")
        }
        assertEquals("Correctly packaged assets must select the reviewed default generation",
            expectedGeneration, shared.name.removePrefix("shared-"))
        assertTrue("Reviewed default startup must use the shared prebuilt table",
            File(shared, "build/zeroinput_public.table.bin").length() > 70_000_000)
        assertFalse("Default startup must not recompile its reviewed public table",
            File(shared.parentFile, shared.name.replace("shared-", "user-") + "/build/zeroinput_public.table.bin").exists())
        assertFalse("Packaged archives must not be deployed into the base cache", File(base, "prebuilt").exists())
        assertFalse("Packaged archives must not be duplicated into the shared generation", File(shared, "prebuilt").exists())
        requireNotNull(factory.createNativeOrNull(ChineseInputOptions())).use { engine ->
            engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
            "nihao".forEach { engine.handle(EngineKey.Character(it.toString())) }
            assertTrue(engine.snapshot.candidates.any { it.text == "你好" })
        }
        factory.close()
        // Simulate the previous release's disposable archives on reuse. Cleanup
        // must remove only these public base/shared copies after verification.
        listOf(base, shared).forEach { directory ->
            File(directory, "prebuilt/fixture.keep").apply {
                parentFile?.mkdirs()
                writeText("legacy public archive fixture")
            }
        }
        val resumed = SystemClock.elapsedRealtime()
        check(factory.warmUp()) { "Public fixture reuse failed: ${factory.runtime.initializationError}" }
        val warm = SystemClock.elapsedRealtime() - resumed
        assertFalse("Legacy base archive copy must be reclaimed", File(base, "prebuilt").exists())
        assertFalse("Legacy shared archive copy must be reclaimed", File(shared, "prebuilt").exists())
        assertEquals(modified, tables.associate { it.name to it.lastModified() })
        assertTrue(File(shared, "build/zeroinput_public.table.bin").isFile)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putLong("xz_cold_deploy_and_native_ms", cold)
            putLong("xz_reused_deploy_and_native_ms", warm)
            putLong("xz_deployed_table_bytes", tables.sumOf { it.length() })
            putString("xz_default_generation", shared.name.removePrefix("shared-"))
            putString("xz_dictionary_sha256", java.security.MessageDigest.getInstance("SHA-256")
                .digest(File(shared, "zeroinput_public.dict.yaml").readBytes())
                .joinToString("") { "%02x".format(it) })
        })
    }

    @Test fun corruptedPrebuiltRecoversThroughControlledRebuildOrRedeployment() = withFixture { factory, root, _ ->
        check(factory.warmUp()) { factory.runtime.initializationError?.toString() ?: "Public fixture failed" }
        val shared = File(root, "rime").listFiles().orEmpty().single { it.name.startsWith("shared-") }
        val table = File(shared, "build/zeroinput_public.table.bin")
        assertTrue(table.isFile)
        factory.close()
        table.writeBytes(byteArrayOf(0, 1, 2, 3))
        assertTrue("Corruption must either recover or preserve a working fallback; it must not hang",
            factory.warmUp() || factory.create().descriptor.isFallback)
        check(factory.warmUp()) { "Retry after corruption failed: ${factory.runtime.initializationError}" }
        requireNotNull(factory.createNativeOrNull()).use { engine ->
            engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
            "nihao".forEach { engine.handle(EngineKey.Character(it.toString())) }
            assertTrue(engine.snapshot.candidates.any { it.text == "你好" })
        }
        assertTrue("Recovered prebuilt or native-rebuilt table must be usable",
            table.length() > 70_000_000 || File(shared.parentFile,
                shared.name.replace("shared-", "user-") + "/build/zeroinput_public.table.bin").length() > 70_000_000)
    }

    @Test fun missingPrebuiltDirectoryIsRedeployedWithoutCompilingTheDefaultDictionary() = withFixture { factory, root, _ ->
        check(factory.warmUp()) { "Public fixture deployment failed" }
        val shared = File(root, "rime").listFiles().orEmpty().single { it.name.startsWith("shared-") }
        val build = File(shared, "build")
        val user = File(shared.parentFile, shared.name.replace("shared-", "user-"))
        factory.close()
        check(build.deleteRecursively()) // disposable public fixture only
        assertTrue("The generation marker must survive the simulated cache loss", File(shared, ".installed").isFile)

        check(factory.warmUp()) { "Missing default cache must recover" }
        assertTrue("Missing prebuilt data must be restored from reviewed APK assets",
            File(build, "zeroinput_public.table.bin").length() > 70_000_000)
        assertFalse("Recovery must not compile the default dictionary into user storage",
            File(user, "build/zeroinput_public.table.bin").exists())
        requireNotNull(factory.createNativeOrNull()).use { engine ->
            engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
            "nihao".forEach { engine.handle(EngineKey.Character(it.toString())) }
            assertTrue(engine.snapshot.candidates.any { it.text == "你好" })
        }
    }

    @Test fun changedDictionaryUsesItsOwnGenerationAndNativeCompilation() = withFixture { factory, root, base ->
        val extra = File(root, "fixture.dict.yaml").apply {
            writeText("---\nname: fixture\nversion: '1'\nsort: by_weight\nuse_preset_vocabulary: false\n...\n" +
                "公开性能测试\tgong kai xing neng ce shi\t2000000000\n")
        }
        val source = dev.zeroinput.engine.api.PublicDictionarySource {
            listOf(dev.zeroinput.engine.api.PublicDictionaryFile("fixture", extra.absolutePath,
                java.security.MessageDigest.getInstance("SHA-256").digest(extra.readBytes()).joinToString("") { "%02x".format(it) }))
        }
        RimeEngineFactory(base, dev.zeroinput.ime.concurrency.BoundedExecutors.singleThread("fixture-native", 1),
            dictionaries = source, resources = object : PublicResourceSource {
                override val revision = 0L
                override fun acquire(id: String): PublicResourceLease? = null
            }).use { changed ->
            assertTrue(changed.runtime.initializationError?.toString() ?: "Changed dictionary fixture failed", changed.warmUp())
            val shared = File(root, "rime").listFiles().orEmpty().single { it.name.startsWith("shared-") }
            assertFalse("A changed dictionary must not reuse the default prebuilt generation",
                File(shared, "build/zeroinput_public.table.bin").exists())
            assertTrue(File(shared.parentFile,
                shared.name.replace("shared-", "user-") + "/build/zeroinput_public.table.bin").exists())
            val userMarker = File(shared.parentFile, shared.name.replace("shared-", "user-") + "/prebuilt/fixture.keep")
            userMarker.parentFile?.mkdirs()
            userMarker.writeText("public cache-boundary fixture")
            fun verifyCustomWord() {
                requireNotNull(changed.createNativeOrNull()).use { engine ->
                    engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                    "gongkaixingnengceshi".forEach { engine.handle(EngineKey.Character(it.toString())) }
                    assertTrue(engine.snapshot.candidates.any { it.text == "公开性能测试" })
                }
            }
            verifyCustomWord()
            changed.close()
            assertTrue("A verified custom generation must retain its compiled table on fallback recovery",
                changed.warmUp())
            assertTrue("Archive cleanup must never enter a user generation", userMarker.isFile)
            verifyCustomWord()
        }
    }

    private fun withFixture(times: Int = 1, test: (RimeEngineFactory, File, android.content.Context) -> Unit) {
        repeat(times) { runFixture(test) }
    }

    private fun runFixture(test: (RimeEngineFactory, File, android.content.Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val graph = (base.applicationContext as ZeroInputApplication).graph
        val root = File(base.cacheDir, "prebuilt-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = root.apply { mkdirs() }
        }
        val noOptionalData = object : PublicResourceSource {
            override val revision = 0L
            override fun acquire(id: String): PublicResourceLease? = null
        }
        try {
            graph.engineExecutor.submit {
                graph.rime.close()
                RimeEngineFactory(context, graph.engineExecutor, resources = noOptionalData).use {
                    test(it, root, context)
                }
            }.get(300, TimeUnit.SECONDS)
        } finally {
            graph.engineExecutor.submit {
                graph.rime.close()
                if (!graph.rime.warmUp()) throw AssertionError("Restore failed", graph.rime.runtime.initializationError)
            }.get(300, TimeUnit.SECONDS)
            root.deleteRecursively() // disposable public fixture only
        }
    }
}
