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
                        for ((width, height) in listOf(320 to 600, 800 to 320)) {
                            val scale = activity.resources.displayMetrics.density
                            val w = (width * scale).toInt(); val h = (height * scale).toInt()
                            // Each manual viewport needs a fresh software draw; retained
                            // dirty flags can otherwise produce an empty second bitmap.
                            views(root).forEach { it.forceLayout(); it.invalidate() }
                            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                            root.layout(0, 0, w, h)
                            views(root).filterIsInstance<EditText>().forEach { assertTrue(it.width > 0 && it.width <= w) }
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            root.draw(Canvas(bitmap))
                            val colors = (0 until w step 16).flatMap { x -> (0 until h step 16).map { y -> bitmap.getPixel(x, y) } }.toSet()
                            assertTrue("Fixture screenshot contains rendered controls", colors.size > 8)
                            File(context.cacheDir, "dictionary-fixture-$language-$mode-$width.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bitmap.recycle()
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
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
