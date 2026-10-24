package dev.zeroinput.ime.settings

import android.content.Context
import android.text.InputType
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R
import java.util.Locale

/** Shared editor for the same preference from Settings and personal phrase management. */
internal object PersonalRankingSettings {
    fun show(context: Context, settings: SettingsRepository, onSaved: () -> Unit): AlertDialog {
        val field = UserPhraseEditor.field(context, R.string.user_dictionary_threshold, 3).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(String.format(Locale.ROOT, "%d", settings.personalAssociationPromotionThreshold))
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.user_dictionary_threshold)
            .setMessage(context.getString(R.string.user_dictionary_threshold_message,
                settings.personalAssociationPromotionThreshold))
            .setView(field).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { field.text.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().toIntOrNull()
                if (value == null || value !in 1..100) {
                    field.error = context.getString(R.string.user_dictionary_threshold_invalid)
                } else {
                    settings.personalAssociationPromotionThreshold = value
                    dialog.dismiss()
                    onSaved()
                }
            }
        }
        dialog.show()
        return dialog
    }
}
