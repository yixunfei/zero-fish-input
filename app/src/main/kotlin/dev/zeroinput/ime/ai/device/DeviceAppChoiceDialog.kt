package dev.zeroinput.ime.ai.device

import android.content.Context
import android.text.InputFilter
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R

internal object DeviceAppChoiceDialog {
    fun filter(choices: List<DeviceAgentAppChoice>, query: String): List<DeviceAgentAppChoice> =
        choices.take(128).filter { it.label.contains(query.trim(), true) || it.packageName.contains(query.trim(), true) }

    fun show(context: Context, choices: List<DeviceAgentAppChoice>, selected: (DeviceAgentAppChoice) -> Unit): AlertDialog {
        val input = EditText(context).apply {
            setHint(R.string.ai_target_search); isSingleLine = true; isSaveEnabled = false
            filters = arrayOf(InputFilter.LengthFilter(96))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        var visible = choices
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, visible.map { "${it.label}\n${it.packageName}" }.toMutableList())
        val list = object : ListView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = (280 * resources.displayMetrics.density).toInt()
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(
                    minOf(maxHeight, View.MeasureSpec.getSize(heightMeasureSpec)), View.MeasureSpec.AT_MOST))
            }
        }.apply { this.adapter = adapter }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            addView(input)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.ai_device_choose_app)
            .setView(body).setNegativeButton(R.string.cancel, null).create()
        list.setOnItemClickListener { _, _, position, _ -> visible.getOrNull(position)?.let(selected); dialog.dismiss() }
        input.doAfterTextChanged {
            visible = filter(choices, it?.toString().orEmpty())
            adapter.clear(); adapter.addAll(visible.map { choice -> "${choice.label}\n${choice.packageName}" })
        }
        dialog.setOnDismissListener { input.text?.clear() }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        return dialog
    }
}
