package dev.zeroinput.ime.ai.page

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication

class PageReferenceSettingsActivity : AppCompatActivity() {
    private val graph get() = (application as ZeroInputApplication).graph
    private lateinit var enabled: MaterialSwitch
    private lateinit var status: TextView
    private var rendering = false
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply { setText(R.string.ai_page_access_title); textSize = 24f })
        content.addView(TextView(this).apply { setText(R.string.ai_page_access_description); textSize = 16f })
        enabled = MaterialSwitch(this).apply {
            setText(R.string.ai_page_enable)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    if (!checked) { graph.settings.aiPageReferencesEnabled = false; render() }
                    else { render(); explain { graph.settings.aiPageReferencesEnabled = true; render() } }
                }
            }
        }
        content.addView(enabled)
        status = TextView(this)
        content.addView(status)
        content.addView(MaterialButton(this).apply {
            setText(R.string.ai_page_authorize)
            setOnClickListener { explain { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        })
        content.addView(MaterialButton(this).apply { setText(R.string.cancel); setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply {
            addView(content)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        rendering = true
        enabled.isChecked = graph.settings.aiPageReferencesEnabled
        rendering = false
        status.setText(if (graph.pageReferences.connected) R.string.ai_page_authorized else R.string.ai_page_not_authorized)
    }

    private fun explain(confirmed: () -> Unit) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setTitle(R.string.ai_page_access_title)
            .setMessage(R.string.ai_page_access_description)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> confirmed() }.create().also {
                it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                it.show()
            }
    }

    override fun onDestroy() { dialog?.dismiss(); dialog = null; super.onDestroy() }

    override fun onStop() { dialog?.dismiss(); dialog = null; super.onStop() }
}
