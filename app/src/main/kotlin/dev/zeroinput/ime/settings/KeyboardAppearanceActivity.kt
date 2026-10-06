package dev.zeroinput.ime.settings

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.ime.ui.R as UiR

class KeyboardAppearanceActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private val settings get() = graph.settings
    private val binding by lazy { KeyboardAppearanceBinding(graph.keyboardBackgrounds) }
    private lateinit var preview: FrameLayout
    private lateinit var options: ScrollView
    private lateinit var status: TextView
    private var keyboard: ZeroInputView? = null
    private var selected = KeyboardAppearance()
    private var operation: AutoCloseable? = null
    private var visible = false
    private var previewTheme: dev.zeroinput.ime.ui.KeyboardTheme? = null
    private val picker = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri ->
        if (uri != null) {
            setBusy(true)
            operation = graph.keyboardBackgrounds.import(uri) { success ->
                setBusy(false)
                if (!success) message(UiR.string.appearance_image_failed)
                refreshOptions()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        selected = settings.keyboardAppearance
        setContentView(content())
        refreshOptions()
    }

    override fun onPause() { keyboard?.cancelPendingGestures(); super.onPause() }
    override fun onStart() { super.onStart(); visible = true; if (::preview.isInitialized) updatePreview() }
    override fun onStop() { visible = false; preview.visibility = View.INVISIBLE; binding.close(); super.onStop() }
    override fun onDestroy() {
        operation?.close()
        binding.close()
        keyboard?.release()
        super.onDestroy()
    }

    private fun content() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        addView(MaterialToolbar(this@KeyboardAppearanceActivity).apply {
            setTitle(R.string.keyboard_appearance)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = getString(R.string.navigate_up)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        status = TextView(context).apply { visibility = View.GONE; setPadding(dp(16), dp(4), dp(16), dp(4)) }
        addView(status)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        addView(LinearLayout(context).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            preview = FrameLayout(context).apply {
                contentDescription = getString(R.string.keyboard_preview)
                visibility = View.INVISIBLE
            }
            addView(preview, if (landscape) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            options = ScrollView(context).apply { isFillViewport = true }
            addView(options, if (landscape) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun refreshOptions() {
        selected = settings.keyboardAppearance
        val scroll = options.scrollY
        options.removeAllViews()
        options.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(SwitchMaterial(context).apply {
                setText(UiR.string.glide_enabled); isChecked = settings.glideTypingEnabled
                minHeight = dp(48); setPadding(dp(16), 0, dp(16), 0)
                setOnCheckedChangeListener { _, enabled -> settings.glideTypingEnabled = enabled }
            })
            addView(AppearanceOptionsView(context, selected, ::select, ::chooseImage,
                { confirmReset(false) }, { confirmReset(true) }))
        })
        options.post { options.scrollTo(0, scroll) }
        updatePreview()
    }

    private fun select(appearance: KeyboardAppearance, commit: Boolean) {
        val deferBlur = !commit && selected.backgroundBlur != appearance.backgroundBlur
        selected = appearance
        if (commit) settings.keyboardAppearance = appearance
        if (!deferBlur) updatePreview()
    }

    private fun updatePreview() {
        if (!visible) return
        if (keyboard == null || previewTheme != selected.theme) {
            keyboard?.release()
            preview.removeAllViews()
            keyboard = ZeroInputView(KeyboardThemeContext.create(this, selected.theme)).apply {
                // No editor, engine or personal-data callbacks exist in the preview.
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }.also { preview.addView(it) }
            previewTheme = selected.theme
        }
        keyboard?.let { view ->
            binding.apply(view, selected) { success ->
                if (visible) preview.visibility = View.VISIBLE
                if (!success) message(UiR.string.appearance_image_missing)
            }
        }
    }

    private fun chooseImage() {
        try { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }
        catch (_: android.content.ActivityNotFoundException) { message(UiR.string.appearance_image_failed) }
    }

    private fun confirmReset(all: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (all) UiR.string.appearance_reset_title else UiR.string.appearance_remove_title)
            .setMessage(if (all) UiR.string.appearance_reset_message else UiR.string.appearance_remove_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (all) settings.keyboardAppearance = KeyboardAppearance()
                binding.close()
                setBusy(true)
                operation = graph.keyboardBackgrounds.remove { success ->
                    setBusy(false)
                    if (!success) message(UiR.string.appearance_delete_failed)
                    refreshOptions()
                }
            }.show()
    }

    private fun setBusy(busy: Boolean) {
        status.visibility = if (busy) View.VISIBLE else View.GONE
        status.setText(UiR.string.appearance_loading)
        fun enable(view: View) {
            view.isEnabled = !busy
            if (view is ViewGroup) for (index in 0 until view.childCount) enable(view.getChildAt(index))
        }
        enable(options)
    }

    private fun message(label: Int) { Toast.makeText(this, label, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
