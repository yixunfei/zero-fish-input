package dev.zeroinput.ime.ai

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R

internal object AiNativeAssistantSetup {
    fun show(activity: Activity, allowed: () -> Boolean): androidx.appcompat.app.AlertDialog =
        MaterialAlertDialogBuilder(activity).setTitle(R.string.ai_native_title).setMessage(R.string.ai_native_setup_notice)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ai_native_choose) { _, _ ->
                if (allowed()) {
                    val request = if (Build.VERSION.SDK_INT >= 29) activity.getSystemService(RoleManager::class.java)
                        ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }?.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) else null
                    runCatching { activity.startActivity(request ?: Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                        .onFailure { Toast.makeText(activity, R.string.ai_native_unavailable, Toast.LENGTH_SHORT).show() }
                }
            }.create().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show() }
}
