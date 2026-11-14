package dev.zeroinput.ime.settings

import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R
import java.util.Locale

internal fun keyboardBackgroundColorDialog(
    context: Context,
    initial: Int,
    apply: (Int) -> Unit,
): androidx.appcompat.app.AlertDialog {
    val channels = intArrayOf(Color.red(initial), Color.green(initial), Color.blue(initial))
    fun color() = Color.rgb(channels[0], channels[1], channels[2])
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val swatch = View(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    val hex = TextView(context)
    fun refreshSwatch() {
        swatch.setBackgroundColor(color())
        hex.text = String.format(Locale.ROOT, "#%06X", color() and 0xFFFFFF)
    }

    val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), 0, dp(24), 0)
        addView(swatch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        addView(hex)
        listOf(R.string.appearance_color_channel_red, R.string.appearance_color_channel_green,
            R.string.appearance_color_channel_blue).forEachIndexed { index, label ->
            val title = TextView(context).apply { text = context.getString(label, channels[index]) }
            addView(title)
            addView(SeekBar(context).apply {
                max = 255
                progress = channels[index]
                minimumHeight = dp(48)
                contentDescription = context.getString(label, progress)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        channels[index] = progress
                        title.text = context.getString(label, progress)
                        bar.contentDescription = title.text
                        refreshSwatch()
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            })
        }
    }
    refreshSwatch()
    return MaterialAlertDialogBuilder(context)
        .setTitle(R.string.appearance_custom_background_color)
        .setView(android.widget.ScrollView(context).apply { addView(body) })
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.save) { _, _ -> apply(color()) }
        .create()
}
