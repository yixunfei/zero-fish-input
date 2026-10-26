package dev.zeroinput.ime

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.settings.MainActivity
import dev.zeroinput.ime.settings.SettingsRepository
import dev.zeroinput.ime.settings.UserDictionaryActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class PersonalRankingSettingsTest {
    @Test fun thresholdDialogIsSecureAndDismissedWhenItsHostIsRecreatedOrClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(2) { attempt ->
                lateinit var dialogRoot: View
                lateinit var field: EditText
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<TextView>().single {
                        it.text.toString().startsWith(activity.getString(R.string.user_dictionary_threshold_value,
                            SettingsRepository(activity).personalPromotionThreshold))
                    }.performClick()
                    dialogRoot = WindowInspector.getGlobalWindowViews().single { root ->
                        descendants(root).filterIsInstance<EditText>().any {
                            it.hint == activity.getString(R.string.user_dictionary_threshold)
                        }
                    }
                    val params = dialogRoot.layoutParams as android.view.WindowManager.LayoutParams
                    assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                    field = descendants(dialogRoot).filterIsInstance<EditText>().single()
                }
                if (attempt == 0) scenario.recreate() else scenario.close()
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertFalse(dialogRoot.isAttachedToWindow)
                    assertTrue(field.text.isEmpty())
                }
            }
        }
    }

    @Test fun promotionThresholdIsDirectlyAccessibleValidatedAndSharedByBothSettingsScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settings = SettingsRepository(instrumentation.targetContext)
        val previous = settings.personalPromotionThreshold
        try {
            settings.personalPromotionThreshold = 3
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val label = activity.getString(R.string.user_dictionary_threshold_value, 3)
                    descendants(activity.window.decorView).filterIsInstance<TextView>().single { it.text == label }.performClick()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = WindowInspector.getGlobalWindowViews().single { root ->
                        descendants(root).filterIsInstance<EditText>().any {
                            it.hint == activity.getString(R.string.user_dictionary_threshold)
                        }
                    }
                    val field = descendants(root).filterIsInstance<EditText>().single()
                    assertEquals("3", field.text.toString())
                    field.setText("0")
                    root.findViewById<View>(android.R.id.button1).performClick()
                    assertNotNull(field.error)
                    assertEquals(3, settings.personalPromotionThreshold)
                    field.setText("5")
                    root.findViewById<View>(android.R.id.button1).performClick()
                    assertEquals(5, settings.personalPromotionThreshold)
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text == activity.getString(R.string.user_dictionary_threshold_value, 5)
                    })
                }
            }
            ActivityScenario.launch(UserDictionaryActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text == activity.getString(R.string.user_dictionary_threshold_value, 5)
                    })
                }
            }
        } finally { settings.personalPromotionThreshold = previous }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()
}
