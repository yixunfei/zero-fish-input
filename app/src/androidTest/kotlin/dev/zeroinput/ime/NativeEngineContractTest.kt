package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.*
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeEngineContractTest {
    @Test fun nativeRimeRunsTheSharedContractWithoutFallback() {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            check(graph.rime.warmUp())
            val engine = checkNotNull(graph.rime.createNativeOrNull())
            check(!engine.descriptor.isFallback)
            InputEngineContract.verify(engine, InputLanguage.CHINESE, "ni", "你")
        }.get(120, TimeUnit.SECONDS)
    }
}
