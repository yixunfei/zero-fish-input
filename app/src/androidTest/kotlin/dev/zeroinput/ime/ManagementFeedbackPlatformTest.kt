package dev.zeroinput.ime

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.expressions.ExpressionManagerAdapter
import dev.zeroinput.ime.management.ManagementStatusView
import dev.zeroinput.ime.settings.SecureClipboardAdapter
import dev.zeroinput.ime.settings.UserDictionaryActivity
import dev.zeroinput.ime.settings.UserDictionaryAdapter
import dev.zeroinput.userdata.PersonalExpression
import dev.zeroinput.userdata.SecureClipboardMetadata
import dev.zeroinput.userdata.UserTerm
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class ManagementFeedbackPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun publicRowsAndFailureActionsFitBothLanguagesThemesAndLargeFonts() = onMain {
        for (language in listOf("zh", "en")) for (night in listOf(false, true)) for (font in listOf(1f, 2f)) {
            val base = instrumentation.targetContext
            val config = Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
                fontScale = font
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val ctx = ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_ZeroInput)
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ctx.getColor(R.color.zero_surface))
            }
            val phrases = UserDictionaryAdapter(onDelete = {})
            phrases.submit(listOf(UserTerm("fixture", "ceshi", "公开测试 Public fixture", InputLanguage.CHINESE, 123456, 0)))
            val expressions = ExpressionManagerAdapter({}, {})
            expressions.submit(listOf(PersonalExpression("fixture", "(public fixture)", "Public fixture", "hello", "happy")))
            val clipboard = SecureClipboardAdapter {}
            clipboard.submitList(listOf(SecureClipboardMetadata("fixture", "公开标签 Public label", 8, 0)))
            for (adapter in listOf(phrases, expressions, clipboard)) {
                root.addView(RecyclerView(ctx).apply {
                    layoutManager = LinearLayoutManager(ctx)
                    this.adapter = adapter
                    itemAnimator = null
                }, LinearLayout.LayoutParams(-1, -2))
            }
            val feedback = ManagementStatusView(ctx)
            feedback.failure(R.string.management_operation_failed) {}
            root.addView(feedback)
            measure(root, 320, 1200)
            for (text in descendants(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }) {
                val layout = checkNotNull(text.layout)
                assertTrue("Text height at $language/$night/$font", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                for (line in 0 until layout.lineCount) {
                    assertEquals("Clipped text at $language/$night/$font", 0, layout.getEllipsisCount(line))
                }
            }
            val reload = root.findViewById<View>(R.id.management_status_retry)
            assertTrue(reload.height >= 48 * ctx.resources.displayMetrics.density)
            val file = File(base.cacheDir, "management-rows-$language-$night-$font.png")
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try { root.draw(Canvas(bitmap)); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    @Test fun rejectedPersonalListReadShowsFailureInsteadOfEmptyAndKeepsReloadAvailable() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UserDictionaryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UserDictionaryActivity
        try {
            await {
                activity.findViewById<TextView>(R.id.management_status_message).text.toString() != activity.getString(R.string.management_loading)
            }
            onMain {
                val field = UserDictionaryActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
                (field.get(activity) as ExecutorService).shutdownNow()
                descendants(activity.window.decorView).filterIsInstance<EditText>().single().setText("public missing fixture")
            }
            await {
                activity.findViewById<TextView>(R.id.management_status_message).text.toString() == activity.getString(R.string.management_load_failed)
            }
            onMain {
                val retry = activity.findViewById<View>(R.id.management_status_retry)
                assertTrue(retry.isEnabled && retry.visibility == View.VISIBLE)
                retry.performClick()
                assertEquals(activity.getString(R.string.management_load_failed),
                    activity.findViewById<TextView>(R.id.management_status_message).text.toString())
                assertEquals(0, descendants(activity.window.decorView).filterIsInstance<RecyclerView>().single().adapter?.itemCount)
            }
        } finally { onMain { activity.finish() }; instrumentation.waitForIdleSync() }
    }

    @Test fun stoppedPersonalPageClearsRowsSearchAndUnsavedDraftsBeforeItResumes() {
        val graph = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph
        val code = "zzmanagementpublicfixture"
        val term = graph.userLexicon.addPhrase(code, "公开管理测试", InputLanguage.CHINESE, 3)
        try {
            ActivityScenario.launch(UserDictionaryActivity::class.java).use { scenario ->
                lateinit var search: EditText
                lateinit var row: TextView
                var drafts = emptyList<EditText>()
                scenario.onActivity { activity ->
                    search = descendants(activity.window.decorView).filterIsInstance<EditText>().single()
                    search.setText(code)
                }
                await {
                    descendants(search.rootView).filterIsInstance<TextView>().any { it.text == term.value }
                }
                scenario.onActivity { activity ->
                    row = descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text == term.value }
                    (row.parent as View).performClick()
                }
                instrumentation.waitForIdleSync()
                onMain {
                    val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews().single {
                        it.findViewById<View>(android.R.id.button1) != null
                    }
                    drafts = descendants(dialog).filterIsInstance<EditText>()
                    assertEquals(3, drafts.size)
                    drafts[1].setText("公开未保存草稿")
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                instrumentation.waitForIdleSync()
                onMain {
                    assertEquals("", search.text.toString())
                    assertEquals("", row.text.toString())
                    assertTrue(drafts.all { it.text.isEmpty() })
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { search.setText(code) }
                await {
                    descendants(search.rootView).filterIsInstance<TextView>().any { it.text == term.value }
                }
            }
        } finally {
            graph.userLexicon.remove(term.id)
            graph.personalization.invalidatePendingWrites()
        }
    }

    @Test fun managementFailureActionsRemainReachableWithLargeTextInShortWindows() {
        val pages = listOf(dev.zeroinput.ime.dictionaries.DictionaryManagerActivity::class.java,
            dev.zeroinput.ime.testing.ExpressionManagerFixtureActivity::class.java)
        for (page in pages) {
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as androidx.appcompat.app.AppCompatActivity
            try {
                await {
                    val message = activity.findViewById<TextView>(R.id.management_status_message)
                    message != null && message.text != activity.getString(R.string.management_loading) &&
                        message.text != activity.getString(R.string.public_dictionary_working)
                }
                onMain {
                    val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    val feedback = descendants(root).filterIsInstance<ManagementStatusView>().single()
                    feedback.failure(R.string.management_operation_failed) {}
                    descendants(root).filterIsInstance<TextView>().forEach {
                        it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, it.textSize * 2)
                    }
                    val parent = root.parent as ViewGroup
                    val params = root.layoutParams
                    val index = parent.indexOfChild(root)
                    val padding = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
                    parent.removeView(root)
                    root.setPadding(0, 0, 0, 0)
                    try {
                        for ((width, height) in listOf(320 to 600, 800 to 320)) {
                            val density = root.resources.displayMetrics.density
                            root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                            val actions = descendants(root).filterIsInstance<TextView>().filter {
                                it.id == R.id.management_status_retry ||
                                    it.text == activity.getString(R.string.public_dictionary_home) ||
                                    it.text == activity.getString(R.string.public_dictionary_cancel)
                            }
                            assertTrue(actions.isNotEmpty())
                            for (action in actions) {
                                action.requestRectangleOnScreen(android.graphics.Rect(0, 0, action.width, action.height), true)
                                val bounds = android.graphics.Rect()
                                action.getDrawingRect(bounds)
                                (root as ViewGroup).offsetDescendantRectToMyCoords(action, bounds)
                                assertTrue("Management action must scroll into the viewport at ${width}x$height: $bounds",
                                    bounds.left >= 0 && bounds.right <= root.width && bounds.top >= 0 && bounds.bottom <= root.height)
                                assertTrue(action.height >= 48 * density)
                            }
                        }
                    } finally {
                        root.setPadding(padding[0], padding[1], padding[2], padding[3])
                        parent.addView(root, index, params)
                    }
                }
            } finally { onMain { activity.finish() }; instrumentation.waitForIdleSync() }
        }
    }

    private fun measure(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            android.os.SystemClock.sleep(25)
        }
        fail("Management page did not settle")
    }
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
