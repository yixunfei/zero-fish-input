package dev.zeroinput.ime.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import dev.zeroinput.ime.ZeroInputApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ZeroInputApplication::class)
class UserDictionaryPickerStateTest {
    @Test fun recreationRetainsThePendingPickerRequestButClearsThePrivateQuery() {
        val first = Robolectric.buildActivity(UserDictionaryActivity::class.java).setup()
        val activity = first.get()
        val search = searchField(activity.window.decorView)
        search.id = View.generateViewId()
        search.setText("private fixture")
        assertFalse(search.isSaveEnabled)
        val field = UserDictionaryActivity::class.java.getDeclaredField("importer").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(activity) as ActivityResultLauncher<Array<String>>).launch(arrayOf("application/octet-stream"))
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        val state = Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        val recreated = Robolectric.buildActivity(UserDictionaryActivity::class.java).setup(state)
        try {
            val restored = recreated.get()
            assertTrue(restored.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null))
            assertTrue(searchField(restored.window.decorView).text.isEmpty())
        } finally { recreated.pause().stop().destroy() }
    }

    private fun searchField(root: View): EditText = checkNotNull(descendants(root).filterIsInstance<EditText>().singleOrNull())

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
