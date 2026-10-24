package dev.zeroinput.ime

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser
import dev.zeroinput.ime.dictionaries.DictionaryEntryActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class DictionaryManagementUiTest {
    @Test fun publicFixtureSearchFitsSmallPortraitAndLandscapeInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        val id = "management_ui_fixture"
        check(graph.publicDictionaries.list().none { it.id == id })
        val previousMode = AppCompatDelegate.getDefaultNightMode()
        val previousLocales = AppCompatDelegate.getApplicationLocales()
        graph.publicDictionaries.install(id, "Fixture", "ICE", "fixture",
            "---\nname: fixture\n...\n公开管理测试\tgong kai guan li ce shi\t42\n".byteInputStream(), RimeTextDictionaryParser())
        try {
            val variants = listOf("en", "zh-CN").flatMap { language ->
                listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES).map { language to it }
            }
            for ((language, mode) in variants) {
                instrumentation.runOnMainSync {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
                    AppCompatDelegate.setDefaultNightMode(mode)
                }
                val activity = instrumentation.startActivitySync(Intent(context, DictionaryEntryActivity::class.java)
                    .putExtra(DictionaryEntryActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DictionaryEntryActivity
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    var displayed = false
                    while (!displayed && System.nanoTime() < deadline) {
                        instrumentation.runOnMainSync {
                            displayed = views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.startsWith("公开管理测试") }
                        }
                        if (!displayed) Thread.sleep(50)
                    }
                    assertTrue("Public fixture search completed", displayed)
                    instrumentation.runOnMainSync {
                        assertEquals(language.substringBefore('-'), activity.resources.configuration.locales[0].language)
                        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        assertTrue(views(root).filterIsInstance<EditText>().all { !it.isSaveEnabled })
                        // Render a detached hierarchy: attached hardware display lists are not a
                        // reliable software screenshot source after a manual viewport change.
                        val parent = root.parent as ViewGroup
                        val index = parent.indexOfChild(root)
                        val params = root.layoutParams
                        val padding = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
                        parent.removeView(root)
                        // Synthetic viewport dimensions exclude the original window's system/IME
                        // insets, which may exceed the height of the landscape fixture.
                        root.setPadding(0, 0, 0, 0)
                        try {
                            for ((width, height) in listOf(320 to 600, 800 to 320)) {
                                captureViewport(root, width, height, language, mode)
                            }
                        } finally {
                            root.setPadding(padding[0], padding[1], padding[2], padding[3])
                            parent.addView(root, index, params)
                        }

                    }
                } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
            }
        } finally {
            graph.publicDictionaries.remove(id)
            instrumentation.runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(previousMode)
                AppCompatDelegate.setApplicationLocales(previousLocales)
            }
        }
    }
    private fun captureViewport(root: View, width: Int, height: Int, language: String, mode: Int) {
        val scale = root.resources.displayMetrics.density
        val w = (width * scale).toInt()
        val h = (height * scale).toInt()
        views(root).forEach { it.forceLayout(); it.invalidate() }
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(root.context.cacheDir, "dictionary-fixture-$language-$mode-$width.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
        val diagnostic = "Dictionary fixture: $language/$mode/${width}x$height"
        val controls = views(root).filterIsInstance<TextView>().filter {
            it is EditText || it.text == root.context.getString(R.string.dictionary_search) ||
                it.text.startsWith("公开管理测试")
        }
        assertEquals(diagnostic, 3, controls.size)
        for (control in controls) {
            val rect = android.graphics.Rect()
            control.getDrawingRect(rect)
            (root as ViewGroup).offsetDescendantRectToMyCoords(control, rect)
            assertTrue(diagnostic, rect.left >= 0 && rect.right <= w && rect.top >= 0 && rect.bottom <= h)
            var ancestor = control.parent
            while (ancestor is ViewGroup && ancestor !== root) {
                val viewport = android.graphics.Rect()
                ancestor.getDrawingRect(viewport)
                root.offsetDescendantRectToMyCoords(ancestor, viewport)
                assertTrue("$diagnostic ${control.javaClass.simpleName} $rect must fit " +
                    "${ancestor.javaClass.simpleName} $viewport", viewport.contains(rect))
                ancestor = ancestor.parent
            }
            assertTrue("$diagnostic ${control.javaClass.simpleName} enabled=${control.isEnabled} " +
                "clickable=${control.isClickable} height=${control.height} target=${48 * scale}",
                control.isEnabled && control.isClickable && control.height >= (48 * scale).toInt())
            val layout = checkNotNull(control.layout)
            assertTrue(diagnostic, layout.height <= control.height - control.compoundPaddingTop - control.compoundPaddingBottom)
            for (line in 0 until layout.lineCount) {
                assertEquals(diagnostic, 0, layout.getEllipsisCount(line))
                assertTrue(diagnostic, layout.getLineWidth(line) <= control.width - control.compoundPaddingLeft - control.compoundPaddingRight + 1)
            }
        }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
