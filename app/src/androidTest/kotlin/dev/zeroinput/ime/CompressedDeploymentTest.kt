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
    @Test fun freshWorkerDeploymentPreservesAllTablesAndReusesVerifiedGeneration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val graph = (base.applicationContext as ZeroInputApplication).graph
        val directory = File(base.cacheDir, "xz-deployment-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = directory
        }
        val noOptionalData = object : PublicResourceSource {
            override val revision = 0L
            override fun acquire(id: String): PublicResourceLease? = null
        }
        try {
            val result = graph.engineExecutor.submit<Bundle> {
                graph.rime.close()
                RimeEngineFactory(context, graph.engineExecutor, resources = noOptionalData).use { factory ->
                    val started = SystemClock.elapsedRealtime()
                    check(factory.warmUp()) { "Public fixture deployment failed: ${factory.runtime.initializationError}" }
                    val cold = SystemClock.elapsedRealtime() - started
                    val root = File(directory, "rime").listFiles().orEmpty().single { it.name.startsWith("base-9-") }
                    val tables = File(root, "dicts").listFiles().orEmpty()
                    assertEquals(16, tables.size)
                    assertTrue(tables.all { it.name.endsWith(".dict.yaml") })
                    val modified = tables.associate { it.name to it.lastModified() }
                    requireNotNull(factory.createNativeOrNull(ChineseInputOptions())).use { engine ->
                        engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                        "nihao".forEach { engine.handle(EngineKey.Character(it.toString())) }
                        assertTrue(engine.snapshot.candidates.any { it.text == "你好" })
                    }
                    factory.close()
                    val resumed = SystemClock.elapsedRealtime()
                    check(factory.warmUp()) { "Public fixture reuse failed: ${factory.runtime.initializationError}" }
                    val warm = SystemClock.elapsedRealtime() - resumed
                    assertEquals(modified, tables.associate { it.name to it.lastModified() })
                    Bundle().apply {
                        putLong("xz_cold_deploy_and_native_ms", cold)
                        putLong("xz_reused_deploy_and_native_ms", warm)
                        putLong("xz_deployed_table_bytes", tables.sumOf { it.length() })
                    }
                }
            }.get(300, TimeUnit.SECONDS)
            instrumentation.sendStatus(0, result)
        } finally {
            graph.engineExecutor.submit { graph.rime.warmUp() }.get(120, TimeUnit.SECONDS)
            directory.deleteRecursively()
        }
    }
}
