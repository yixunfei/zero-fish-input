package dev.zeroinput.ime

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ime.settings.MainActivity
import dev.zeroinput.ime.settings.SettingsRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class IncognitoConsentTest {
    @Test fun enablingRequiresConfirmationAndCancellationOrRecreationDoesNotEnable() {
        val settings = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val previous = settings.incognitoMode
        try {
            settings.incognitoMode = false
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    toggle(activity).performClick()
                    assertFalse(settings.incognitoMode)
                    assertFalse(toggle(activity).isChecked)
                    dialog(activity).findViewById<View>(android.R.id.button2).performClick()
                }
                scenario.onActivity { activity ->
                    assertFalse(settings.incognitoMode)
                    toggle(activity).performClick()
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertFalse(settings.incognitoMode)
                    toggle(activity).performClick()
                }
                scenario.onActivity { activity ->
                    val root = dialog(activity)
                    val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap))
                    java.io.File(activity.getExternalFilesDir(null), "incognito-consent.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                    dialog(activity).findViewById<View>(android.R.id.button1).performClick()
                }
                scenario.onActivity { activity ->
                    assertTrue(settings.incognitoMode)
                    assertTrue(toggle(activity).isChecked)
                    toggle(activity).performClick()
                    assertFalse(settings.incognitoMode)
                    toggle(activity).performClick()
                    assertFalse(settings.incognitoMode)
                    dialog(activity).findViewById<View>(android.R.id.button2).performClick()
                }
            }
        } finally { settings.incognitoMode = previous }
    }

    private fun toggle(activity: MainActivity) = descendants(activity.window.decorView)
        .filterIsInstance<MaterialSwitch>().single { it.text == activity.getString(R.string.setting_incognito) }

    private fun dialog(activity: MainActivity) = WindowInspector.getGlobalWindowViews().single { root ->
        descendants(root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.incognito_copy_message) }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
