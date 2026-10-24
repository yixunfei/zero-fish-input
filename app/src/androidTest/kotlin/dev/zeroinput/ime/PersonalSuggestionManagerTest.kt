package dev.zeroinput.ime

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.settings.UserDictionaryActivity
import dev.zeroinput.ime.settings.UserPhraseEdit
import dev.zeroinput.ime.settings.UserPhraseEditor
import dev.zeroinput.userdata.UserTerm
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalSuggestionManagerTest {
    @Test fun landscapeEditorKeepsFieldsScrollableAndActionsVisible() {
        ActivityScenario.launch(UserDictionaryActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            lateinit var dialog: AlertDialog
            scenario.onActivity { activity ->
                assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                    activity.resources.configuration.orientation)
                dialog = UserPhraseEditor.show(activity,
                    UserTerm("fixture", "cs", "测试", InputLanguage.CHINESE, 3, 0), { false }) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = checkNotNull(dialog.window).decorView
                val scroll = descendants(root).filterIsInstance<android.widget.ScrollView>()
                    .single { fields(it).isNotEmpty() }
                assertTrue(scroll.height >= 48 * activity.resources.displayMetrics.density)
                assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isShown)
                fields(root).forEach { field ->
                    assertTrue(descendants(root).filterIsInstance<android.widget.TextView>()
                        .any { it.labelFor == field.id && it.text == field.hint })
                }
                scroll.isSmoothScrollingEnabled = false
                scroll.fullScroll(View.FOCUS_DOWN)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val root = checkNotNull(dialog.window).decorView
                val languages = descendants(root).filterIsInstance<android.widget.RadioGroup>().single()
                val visible = android.graphics.Rect()
                assertTrue(languages.getGlobalVisibleRect(visible))
                assertEquals(languages.height, visible.height())
                dialog.dismiss()
            }
        }
    }

    @Test fun editorValidatesWeightRetainsIdentityFieldsAndClearsDismissedContent() {
        ActivityScenario.launch(UserDictionaryActivity::class.java).use { scenario ->
            for ((width, height) in listOf(320 to 600, 411 to 800, 800 to 320)) {
                var saved: UserPhraseEdit? = null
                lateinit var dialog: AlertDialog
                lateinit var fields: List<EditText>
                scenario.onActivity { activity ->
                    val term = UserTerm("fixture", "cs", "测试", InputLanguage.CHINESE, 123456, 0)
                    dialog = UserPhraseEditor.show(activity, term, { false }) { value, _ -> saved = value }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val window = checkNotNull(dialog.window)
                    assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    val root = window.decorView
                    val density = activity.resources.displayMetrics.density
                    root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    fields = fields(root)
                    assertEquals(3, fields.size)
                    assertTrue(fields.all { !it.isSaveEnabled && it.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO })
                    val weight = fields.single { it.hint == activity.getString(R.string.user_dictionary_frequency) }
                    assertEquals("123456", weight.text.toString())
                    val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap))
                    java.io.File(activity.filesDir, "personal-manager-$width.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                    weight.setText("0")
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertNull(saved)
                    assertNotNull(weight.error)
                    weight.setText("4")
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertEquals(UserPhraseEdit("cs", "测试", InputLanguage.CHINESE, 4), saved)
                    assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).width > 0)
                    dialog.dismiss()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { assertTrue(fields.all { it.text.isEmpty() }) }
            }
        }
    }

    @Test fun bundledPinyinSearchFindsAnEncryptedPhraseSavedWithAnUnrelatedShortcut() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication
        val repository = application.graph.userLexicon
        val term = repository.addPhrase("zz-personal-search-fixture", "测试", InputLanguage.CHINESE, 3)
        try {
            for (query in listOf("ceshi", "ce shi", "cs")) {
                assertTrue(repository.search(query).any { it.id == term.id })
            }
            repository.updatePhrase(term.id, "zz-personal-search-fixture", "测试", InputLanguage.CHINESE, 6)
            assertEquals(6, repository.search("ceshi").first { it.id == term.id }.frequency)
        } finally {
            repository.remove(term.id)
            application.graph.personalization.invalidatePendingWrites()
        }
    }

    private fun fields(view: View): List<EditText> = if (view is EditText) listOf(view)
        else if (view is ViewGroup) (0 until view.childCount).flatMap { fields(view.getChildAt(it)) }
        else emptyList()

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()
}
