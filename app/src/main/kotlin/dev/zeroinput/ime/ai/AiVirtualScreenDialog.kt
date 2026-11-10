package dev.zeroinput.ime.ai

import android.app.KeyguardManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R

/** Host must close on stop, lock, or configuration revocation. No preview is saved or sent. */
internal class AiVirtualScreenDialog(private val context: Context, private val current: () -> Boolean) : AutoCloseable {
    private var dialog: AlertDialog? = null
    private var surface: Surface? = null
    private val status = TextView(context).apply { setText(R.string.ai_virtual_idle); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val texture = TextureView(context).apply { isSaveEnabled = false }
    private val screen = AiVirtualScreen(context) { status.setText(R.string.ai_virtual_stopped) }
    fun show() {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding); isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            addView(TextView(context).apply { setText(R.string.ai_virtual_limits) })
            addView(texture, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, padding * 11))
            addView(status)
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                value.setDefaultBufferSize(AiVirtualScreen.WIDTH, AiVirtualScreen.HEIGHT)
                surface = Surface(value); dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean { release(); return true }
        }
        dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.ai_virtual_title)
            .setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton(R.string.ai_virtual_start, null).setNeutralButton(R.string.ai_run_stop, null)
            .setNegativeButton(R.string.cancel, null).create().also { value ->
                value.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                value.setOnDismissListener { release(); dialog = null }
                value.show()
                value.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    isEnabled = surface != null
                    setOnClickListener {
                        if (current() && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                            val ok = surface?.let(screen::start) == true
                            status.setText(if (ok) R.string.ai_virtual_running else R.string.ai_virtual_unavailable)
                        } else close()
                    }
                }
                value.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { screen.close() }
            }
    }
    private fun release() { screen.close(); surface?.release(); surface = null }
    override fun close() { release(); dialog?.dismiss(); dialog = null }
}
