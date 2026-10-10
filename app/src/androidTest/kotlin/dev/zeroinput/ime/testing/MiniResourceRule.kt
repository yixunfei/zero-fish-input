package dev.zeroinput.ime.testing

import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.languagepack.PublicResourceStore
import org.junit.rules.ExternalResource

/** Explicit test setup; prior enablement is restored even when model assertions fail. */
class MiniResourceRule : ExternalResource() {
    private lateinit var resources: PublicResourceStore
    private var wasEnabled = false
    override fun before() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication
        resources = app.graph.publicResources
        val mini = resources.list().single { it.pack.id == "mini-int8" }
        check(mini.bundled || mini.downloaded) { "Install the full test APK or Mini resource fixture" }
        wasEnabled = mini.enabled
        resources.setEnabled("mini-int8", true)
    }
    override fun after() { resources.setEnabled("mini-int8", wasEnabled) }
}
