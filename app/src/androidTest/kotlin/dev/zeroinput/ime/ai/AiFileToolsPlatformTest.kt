package dev.zeroinput.ime.ai

import android.content.*
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.view.View
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AiFileToolsPlatformTest {
    @Test fun pendingApprovalCollapsesSetupAndKeepsItsDecisionSeparateFromLongArguments() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = androidx.appcompat.view.ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_ZeroInput)
            val view = AiToolTaskView(context)
            val tool = AiFileTools.definitions(listOf("file1")).first { it.name == AiFileTools.READ }
            view.configure(listOf(tool), setOf(tool.id), listOf("file1: Public.txt"), AiWorkspaceContext(), true, true, "Public model")
            var approved: Pair<String, Boolean>? = null
            view.onApprove = { token, accepted -> approved = token to accepted }
            view.event(AiToolRunEvent(AiToolRunState.APPROVAL, approval = "public-approval", tool = tool,
                call = AiToolCall("call", tool.name, """{"file":"file1","offset":0,"length":4000}""")))
            val views = descendants(view)
            assertEquals(View.GONE, views.filterIsInstance<android.widget.Button>().single { it.text == context.getString(R.string.ai_tool_pick) }.parent.let { it as View }.visibility)
            assertNull(approved)
            views.filterIsInstance<android.widget.Button>().single { it.text == context.getString(R.string.ai_tool_decline) }.performClick()
            assertEquals("public-approval" to false, approved)
            view.event(AiToolRunEvent(AiToolRunState.STOPPED))
            assertEquals(View.GONE, views.filterIsInstance<android.widget.Button>().single { it.text == context.getString(R.string.ai_tool_decline) }.parent.let { it as View }.visibility)
        }
    }
    private fun descendants(root: android.view.View): List<android.view.View> = listOf(root) + if (root is android.view.ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
    @Test fun taskComposerRemainsVisibleWithLongDraftInBothThemesAndSmallLandscape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frames = mutableListOf<Pair<String, android.graphics.Bitmap>>()
        try {
            instrumentation.runOnMainSync {
                for (night in listOf(false, true)) for (landscape in listOf(false, true)) {
                    val config = android.content.res.Configuration(instrumentation.targetContext.resources.configuration).apply {
                        fontScale = 2f
                        uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
                    }
                    val context = androidx.appcompat.view.ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_ZeroInput)
                    val view = AiToolTaskView(context)
                    view.configure(AiFileTools.definitions(listOf("file1")), setOf("files/read"), listOf("file1: Public.txt"),
                        AiWorkspaceContext(), false, false, "Public fixture model")
                    view.findViewById<EditText>(R.id.ai_tool_prompt).setText("Public task draft\n".repeat(8))
                    val width = ((if (landscape) 600 else 360) * context.resources.displayMetrics.density).toInt()
                    val height = ((if (landscape) 320 else 720) * context.resources.displayMetrics.density).toInt()
                    view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                    val start = view.findViewById<android.view.View>(R.id.ai_tool_start)
                    val rect = android.graphics.Rect(); assertTrue(start.getGlobalVisibleRect(rect)); assertTrue(rect.bottom <= height)
                    assertTrue(rect.height() == start.height)
                    if (InstrumentationRegistry.getArguments().getString("toolVisuals") == "true") {
                        val image = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                        val color = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.colorBackground, color, true)
                        val canvas = android.graphics.Canvas(image); canvas.drawColor(color.data); view.draw(canvas)
                        frames += "tool-$night-$landscape.png" to image
                    }
                }
            }
            if (frames.isNotEmpty()) {
                val directory = File(instrumentation.targetContext.cacheDir, "ai-tool-public-layout").apply { mkdirs() }
                frames.forEach { (name, bitmap) -> File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            }
        } finally { frames.forEach { it.second.recycle() } }
    }

    @Test fun selectedFileReadSearchAndRevocationUseOnlyTheBoundedFixture() = fixture { provider, resolver ->
        provider.file.writeText("Public first line\nPublic second line")
        val work = ArrayDeque<Runnable>(); val results = mutableListOf<AiToolResult>()
        val executor = AiFileToolExecutor(resolver, mapOf("file1" to URI), Executor { work.add(it) })
        executor.execute(AiToolCall("read", AiFileTools.READ, """{"file":"file1","offset":0,"length":6}"""), { true }, results::add)
        assertEquals(0, provider.opens); work.removeFirst().run()
        assertTrue(results.single().content.endsWith("Public"))
        executor.execute(AiToolCall("search", AiFileTools.SEARCH, """{"file":"file1","query":"second","ignore_case":false}"""), { true }, results::add)
        work.removeFirst().run(); assertTrue(results.last().content.contains("second line"))
        val before = provider.opens
        executor.execute(AiToolCall("cancel", AiFileTools.READ, """{"file":"file1","offset":0,"length":6}"""), { true }, results::add).cancel()
        work.removeFirst().run(); assertEquals(before, provider.opens); assertEquals(2, results.size)
        provider.file.writeBytes(ByteArray(65_537) { 65 })
        executor.execute(AiToolCall("big", AiFileTools.READ, """{"file":"file1","offset":0,"length":6}"""), { true }, results::add)
        work.removeFirst().run(); assertEquals(AiToolResultStatus.FAILED, results.last().status)
    }
    @Test fun saveAsRequiresDestinationConfirmationAndNeverRetriesUncertainWrites() = fixture { provider, resolver ->
        val work = ArrayDeque<Runnable>(); val results = mutableListOf<AiToolResult>()
        var selected: ((Uri?) -> Unit)? = null
        val executor = AiSaveAsToolExecutor(resolver, Executor { work.add(it) }, { output, completed ->
            assertEquals("Public.txt", output.name); selected = completed; AutoCloseable {}
        })
        val call = AiToolCall("save", AiFileTools.SAVE, """{"name":"Public.txt","content":"Public output"}""")
        executor.execute(call, { true }, results::add)
        assertEquals(0, provider.opens); assertTrue(work.isEmpty())
        selected!!(URI); selected!!(URI)
        assertEquals(1, work.size); work.removeFirst().run()
        assertEquals("Public output", provider.file.readText()); assertEquals(AiToolResultStatus.SUCCESS, results.single().status)
        val cancelled = executor.execute(call, { true }, results::add)
        cancelled.cancel(); selected!!(URI); assertTrue(work.isEmpty())
        executor.execute(call, { true }, results::add); selected!!(URI); work.removeFirst().run()
        assertEquals(AiToolResultStatus.FAILED, results.last().status); assertEquals(1, provider.opens)
        provider.failOpen = true
        executor.execute(call, { true }, results::add); selected!!(OTHER_URI); work.removeFirst().run()
        assertEquals(AiToolResultStatus.UNKNOWN, results.last().status); assertTrue(work.isEmpty()); assertEquals(2, provider.opens)
    }
    @Test fun privateTaskEntryDropsIncomingDataAndClearsDraftOnFinish() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, AiToolTaskActivity::class.java)
            .putExtra(Intent.EXTRA_TEXT, "Untrusted public fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AiToolTaskActivity
        lateinit var prompt: EditText
        val destroyed = java.util.concurrent.CountDownLatch(1)
        val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleCallback { owner, stage ->
            if (owner === activity && stage == androidx.test.runner.lifecycle.Stage.DESTROYED) destroyed.countDown()
        }
        val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        try {
            instrumentation.runOnMainSync {
                monitor.addLifecycleCallback(lifecycle)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertNull(activity.intent.getStringExtra(Intent.EXTRA_TEXT))
                assertFalse(activity.packageManager.getActivityInfo(activity.componentName, 0).exported)
                prompt = activity.findViewById(R.id.ai_tool_prompt); prompt.setText("Public task draft")
                activity.finish()
            }
            assertTrue("Task Activity must finish", destroyed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertEquals("", prompt.text.toString()) }
        } finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(lifecycle); activity.finish() } }
    }
    private fun fixture(test: (Provider, ContentResolver) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("ai-tool-public-fixture", ".txt", context.cacheDir)
        try { val provider = Provider(file); test(provider, ContentResolver.wrap(provider)) } finally { file.delete() }
    }
    private class Provider(val file: File) : ContentProvider() {
        var opens = 0; var failOpen = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            opens++; check(uri == URI || uri == OTHER_URI); if (failOpen) throw SecurityException("Fixture denied")
            return AssetFileDescriptor(ParcelFileDescriptor.open(file, if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY
                else ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
    private companion object {
        val URI = Uri.parse("content://tool-fixture/public")
        val OTHER_URI = Uri.parse("content://tool-fixture/other")
    }
}
