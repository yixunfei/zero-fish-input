package dev.zeroinput.ime.ai

import android.content.Context
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ai.api.AiSkill
import dev.zeroinput.ime.R
import java.util.UUID

internal object AiSkillEditorDialog {
    fun show(context: Context, existing: AiSkill?, imported: AiSkillDocument.Draft?, allowed: () -> Boolean,
        save: (AiSkill) -> Boolean): AlertDialog {
        fun input(hint: Int, value: String, limit: Int) = EditText(context).apply {
            setHint(hint); setText(value); maxLines = 6; isSaveEnabled = false
            filters = arrayOf(InputFilter.LengthFilter(limit))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val name = input(R.string.ai_skill_name, existing?.name ?: imported?.name.orEmpty(), 80)
        val description = input(R.string.ai_library_description, existing?.description ?: imported?.description.orEmpty(), 512)
        val instructions = input(R.string.ai_skill_instructions, existing?.instructions ?: imported?.instructions.orEmpty(), AiSkill.MAX_BYTES)
        val tools = input(R.string.ai_skill_required_tools, (existing?.requiredTools ?: imported?.tools.orEmpty()).joinToString("\n"), 1_552)
        val error = TextView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; isSaveEnabled = false
            val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            addView(TextView(context).apply { setText(R.string.ai_skill_notice) })
            listOf(name, description, instructions, tools, error).forEach(::addView)
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.ai_skill_edit)
            .setView(ScrollView(context).apply { addView(body) }).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_library_save, null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { listOf(name, description, instructions, tools).forEach { it.text?.clear() }; body.removeAllViews() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!allowed()) { dialog.dismiss(); return@setOnClickListener }
            val value = runCatching { AiSkill(existing?.id ?: UUID.randomUUID().toString(), name.text.toString().trim(),
                description.text.toString().trim(), instructions.text.toString().trim(),
                tools.text.toString().lines().map(String::trim).filter(String::isNotEmpty)) }.getOrNull()
            if (value == null || !save(value)) error.setText(R.string.ai_library_invalid) else dialog.dismiss()
        }
        return dialog
    }
}
