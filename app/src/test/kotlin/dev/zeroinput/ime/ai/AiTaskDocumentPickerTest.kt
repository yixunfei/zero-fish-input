package dev.zeroinput.ime.ai

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import dev.zeroinput.ime.R
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiTaskDocumentPickerTest {
    @Test fun expiredPickerCannotGrantFilesAndOldMetadataCannotCancelANewSelection() {
        val host = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = host.get(); activity.setTheme(R.style.Theme_ZeroInput)
        host.create()
        val worker = ArrayDeque<Runnable>(); val selected = mutableListOf<Uri>(); var expired = 0
        val picker = AiTaskDocumentPicker(activity, Executor { worker.add(it) }, AiDataGeneration(), Handler(Looper.getMainLooper()),
            { true }, { expired++ })
        host.start().resume().visible()
        fun choose(): Int {
            picker.select { uri, _ -> selected += uri }
            val launch = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
            return launch.requestCode
        }
        fun returned(request: Int, suffix: String) {
            assertTrue(activity.activityResultRegistry.dispatchResult(request, Activity.RESULT_OK, Intent().setData(Uri.parse("content://fixture/$suffix"))))
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            val first = choose()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
            assertEquals(1, expired); assertFalse(picker.external)
            returned(first, "expired")
            assertTrue(worker.isEmpty()); assertTrue(selected.isEmpty())
            val old = choose(); returned(old, "old")
            assertEquals(1, worker.size)
            picker.close()
            val fresh = choose()
            worker.removeFirst().run(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(picker.external); assertTrue(selected.isEmpty())
            returned(fresh, "fresh")
            worker.removeFirst().run(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(Uri.parse("content://fixture/fresh")), selected)
            assertFalse(picker.external)
        } finally { picker.close(); host.pause().stop().destroy() }
    }
}
