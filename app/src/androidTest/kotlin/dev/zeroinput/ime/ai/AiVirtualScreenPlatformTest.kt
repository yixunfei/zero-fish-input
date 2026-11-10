package dev.zeroinput.ime.ai

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.TextureView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiVirtualScreenPlatformTest {
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun leavingTheProductionCapabilityDialogReleasesItsDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(DisplayManager::class.java)
        val initial = manager.displays.map { it.displayId }.toSet()
        val activity = instrumentation.startActivitySync(Intent(context, AiToolTaskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun button(label: Int) = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::views)
            .filterIsInstance<android.widget.Button>().firstOrNull { it.isShown && it.text.toString() == context.getString(label) }
        try {
            instrumentation.runOnMainSync { requireNotNull(button(dev.zeroinput.ime.R.string.ai_virtual_title)).performClick() }
            var ready = false
            repeat(100) {
                if (!ready) {
                    instrumentation.runOnMainSync { ready = button(dev.zeroinput.ime.R.string.ai_virtual_start)?.isEnabled == true }
                    if (!ready) android.os.SystemClock.sleep(20)
                }
            }
            assertTrue("Preview surface must become ready", ready)
            instrumentation.runOnMainSync { requireNotNull(button(dev.zeroinput.ime.R.string.ai_virtual_start)).performClick() }
            instrumentation.waitForIdleSync()
            val created = manager.displays.map { it.displayId }.toSet() - initial
            assertEquals(1, created.size)
            instrumentation.runOnMainSync { activity.finish() }
            var released = false
            repeat(100) {
                if (!released) { instrumentation.waitForIdleSync(); released = created.none { manager.getDisplay(it) != null }; if (!released) android.os.SystemClock.sleep(20) }
            }
            assertTrue("Leaving the task page must release the preview", released)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun views(root: android.view.View): List<android.view.View> = listOf(root) + if (root is android.view.ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    @Test fun actualTexturePreviewContainsOnlyThePublicFixtureAndReleasesOnSurfaceLoss() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val rendered = java.util.concurrent.CountDownLatch(1)
        var screen: AiVirtualScreen? = null
        var surface: Surface? = null
        lateinit var preview: TextureView
        try {
            instrumentation.runOnMainSync {
                preview = TextureView(activity).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            texture.setDefaultBufferSize(AiVirtualScreen.WIDTH, AiVirtualScreen.HEIGHT)
                            surface = Surface(texture)
                            screen = AiVirtualScreen(activity) {}
                            assertTrue(screen!!.start(surface!!))
                        }
                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { rendered.countDown() }
                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            screen?.close(); surface?.release(); surface = null; return true
                        }
                    }
                }
                activity.setContentView(preview)
            }
            assertTrue("Public virtual-display buffer must render", rendered.await(8, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val bitmap = requireNotNull(preview.getBitmap(AiVirtualScreen.WIDTH, AiVirtualScreen.HEIGHT))
                try {
                    assertEquals(android.graphics.Color.rgb(24, 48, 64), bitmap.getPixel(20, 20))
                    if (InstrumentationRegistry.getArguments().getString("toolVisuals") == "true") {
                        val file = java.io.File(activity.cacheDir, "virtual-public-preview.png")
                        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally { bitmap.recycle() }
                activity.setContentView(android.widget.TextView(activity))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertNull(screen?.displayId) }
        } finally { instrumentation.runOnMainSync { screen?.close(); surface?.release(); activity.finish() } }
    }
    @Test fun privateDisplayUsesOwnPublicContentAndReleaseRemovesItWithoutMainDisplayFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var released = 0
        lateinit var screen: AiVirtualScreen
        lateinit var texture: SurfaceTexture
        lateinit var surface: Surface
        var id = -1
        instrumentation.runOnMainSync {
            texture = SurfaceTexture(false); texture.setDefaultBufferSize(AiVirtualScreen.WIDTH, AiVirtualScreen.HEIGHT)
            surface = Surface(texture); screen = AiVirtualScreen(context) { released++ }
            assertTrue(screen.start(surface)); id = requireNotNull(screen.displayId)
            assertNotEquals(Display.DEFAULT_DISPLAY, id)
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
            assertTrue(display.flags and Display.FLAG_PRIVATE != 0)
            assertFalse(display.flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0)
            if (Build.VERSION.SDK_INT >= 29) {
                // Eligibility alone is not an automation grant. Never actually start a foreign Activity.
                val other = Intent().setComponent(ComponentName(instrumentation.context.packageName,
                    "dev.zeroinput.ime.ai.page.ExternalPageFixture"))
                assertFalse(context.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(context, id, other))
            }
        }
        try {
            instrumentation.runOnMainSync { screen.close(); screen.close(); assertNull(screen.displayId); assertEquals(1, released) }
            instrumentation.waitForIdleSync()
            assertNull(context.getSystemService(DisplayManager::class.java).getDisplay(id))
        } finally { instrumentation.runOnMainSync { screen.close(); surface.release(); texture.release() } }
    }
    @Test fun invalidSurfaceDoesNotCreateOrRetainADisplay() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val texture = SurfaceTexture(false); val surface = Surface(texture); surface.release()
            val screen = AiVirtualScreen(InstrumentationRegistry.getInstrumentation().targetContext) {}
            try { assertFalse(screen.start(surface)); assertNull(screen.displayId) } finally { screen.close(); texture.release() }
        }
    }
}
