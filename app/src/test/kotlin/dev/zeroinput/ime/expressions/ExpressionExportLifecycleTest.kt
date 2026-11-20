package dev.zeroinput.ime.expressions

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import dev.zeroinput.ime.R
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.EncryptedExportCipher
import dev.zeroinput.userdata.PersonalExpressionRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpressionExportLifecycleTest {
    @Test fun resultBeforeResumeAndResultDuringRefreshBothProduceImportableExports() {
        for (beforeResume in listOf(true, false)) {
            val controller = Robolectric.buildActivity(ExportFixtureActivity::class.java).create().start().resume().visible()
            val activity = controller.get()
            settle(activity)
            launchExport(activity)
            val launched = shadowOf(activity).nextStartedActivityForResult
            controller.pause().stop().restart().start()
            val output = java.io.File(activity.cacheDir, "public-export-$beforeResume.zexp").apply { writeBytes(byteArrayOf()) }
            if (!beforeResume) controller.resume()
            assertTrue(activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(output))))
            if (beforeResume) controller.resume()
            settle(activity)
            assertEquals(1, activity.encryptions)
            assertTrue(output.length() > 0)
            val destination = ExportFixtureActivity.repository()
            assertEquals(1, destination.importEncrypted(output.readBytes()))
            assertEquals("public fixture", destination.read().custom.single().value)
            controller.pause().stop().destroy()
        }
    }

    @Test fun cancelledPickerNeverCallsTheExporter() {
        val controller = Robolectric.buildActivity(ExportFixtureActivity::class.java).create().start().resume()
        val activity = controller.get()
        settle(activity)
        launchExport(activity)
        val launched = shadowOf(activity).nextStartedActivityForResult
        controller.pause().stop().restart().start()
        activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_CANCELED, null)
        controller.resume()
        settle(activity)
        assertEquals(0, activity.encryptions)
        controller.pause().stop().destroy()
    }

    @Test fun dataDeletionWhileChoosingADestinationRevokesTheExport() {
        val controller = Robolectric.buildActivity(ExportFixtureActivity::class.java).create().start().resume()
        val activity = controller.get()
        settle(activity)
        launchExport(activity)
        val launched = shadowOf(activity).nextStartedActivityForResult
        controller.pause().stop()
        activity.clearFixture()
        controller.restart().start()
        val output = java.io.File(activity.cacheDir, "revoked-export.zexp").apply { writeBytes(byteArrayOf()) }
        activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(output)))
        controller.resume()
        settle(activity)
        assertEquals(0, activity.encryptions)
        assertEquals(0L, output.length())
        assertEquals(activity.getString(R.string.expression_export_failed), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        controller.pause().stop().destroy()
    }

    @Test fun recreationRevokesTheDestinationAndReportsAnIncompleteExport() {
        val controller = Robolectric.buildActivity(ExportFixtureActivity::class.java).create().start().resume()
        val activity = controller.get()
        settle(activity)
        launchExport(activity)
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        val recreated = Robolectric.buildActivity(ExportFixtureActivity::class.java).create(state).start().resume()
        settle(recreated.get())
        assertEquals(0, recreated.get().encryptions)
        assertEquals(recreated.get().getString(R.string.expression_export_failed), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        recreated.pause().stop().destroy()
    }

    @Test fun failedDestinationWriteReportsFailureWithoutRetry() {
        val controller = Robolectric.buildActivity(ExportFixtureActivity::class.java).create().start().resume()
        val activity = controller.get()
        settle(activity)
        launchExport(activity)
        val launched = shadowOf(activity).nextStartedActivityForResult
        val output = java.io.File(activity.cacheDir, "missing-parent/fixture.zexp")
        activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(output)))
        settle(activity)
        assertEquals(1, activity.encryptions)
        assertFalse(output.exists())
        assertEquals(activity.getString(R.string.expression_export_failed), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        controller.pause().stop().destroy()
    }

    private fun settle(activity: ExportFixtureActivity) {
        repeat(4) {
            (field(activity, "worker") as ExecutorService).submit {}.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun launchExport(activity: ExportFixtureActivity) {
        ExpressionManagerActivity::class.java.getDeclaredMethod("confirmExport").apply { isAccessible = true }.invoke(activity)
        (field(activity, "confirmation") as androidx.appcompat.app.AlertDialog)
            .getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun field(activity: ExportFixtureActivity, name: String): Any =
        requireNotNull(ExpressionManagerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity))
}

class ExportFixtureActivity : ExpressionManagerActivity() {
    var encryptions = 0
    override val repository = repository { encryptions++ }.also {
        it.save(null, "public fixture", "fixture", "public", "custom")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ZeroInput)
        super.onCreate(savedInstanceState)
    }
    fun clearFixture() { repository.clear() }

    companion object {
        // A reversible fixture cipher lets the test verify the repository format round trip.
        fun repository(onEncrypt: () -> Unit = {}): PersonalExpressionRepository {
            var stored: ByteArray? = null
            return PersonalExpressionRepository(object : EncryptedStore {
                override fun read() = stored?.copyOf()
                override fun write(plaintext: ByteArray) { stored = plaintext.copyOf() }
                override fun delete(deleteKey: Boolean) { stored = null }
            }, object : EncryptedExportCipher {
                override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                    onEncrypt()
                    return plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
                }
                override fun decrypt(payload: ByteArray, associatedData: ByteArray) =
                    payload.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
            })
        }
    }
}
